/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.mllib.clustering

import java.util.Random

import scala.collection.mutable
import scala.reflect.ClassTag

import breeze.linalg.{DenseVector => BDV, SparseVector => BSV, sum => brzSum}

import org.apache.spark.broadcast.Broadcast
import org.apache.spark.graphx._
import org.apache.spark.Logging
import org.apache.spark.mllib.linalg.distributed.{MatrixEntry, RowMatrix}
import org.apache.spark.mllib.linalg.{DenseVector => SDV, SparseVector => SSV, Vector => SV}
import org.apache.spark.rdd.RDD
import org.apache.spark.serializer.KryoRegistrator
import org.apache.spark.storage.StorageLevel
import org.apache.spark.SparkContext._
import org.apache.spark.util.random.XORShiftRandom

import LDA._

class LDA private[mllib](
  @transient var corpus: Graph[VD, ED],
  val numTopics: Int,
  val numTerms: Int,
  val alpha: Double,
  val beta: Double,
  val alphaAS: Double,
  @transient val storageLevel: StorageLevel)
  extends Serializable with Logging {

  def this(docs: RDD[(DocId, SSV)],
    numTopics: Int,
    alpha: Double,
    beta: Double,
    alphaAS: Double,
    storageLevel: StorageLevel = StorageLevel.MEMORY_AND_DISK,
    computedModel: Broadcast[LDAModel] = null) {
    this(initializeCorpus(docs, numTopics, storageLevel, computedModel),
      numTopics, docs.first()._2.size, alpha, beta, alphaAS, storageLevel)
  }

  // scalastyle:off
  /**
   * 语料库文档数
   */
  val numDocs = docVertices.count()

  /**
   * 语料库总的词数(包含重复)
   */
  private val numTokens = corpus.edges.map(e => e.attr.size.toDouble).sum().toLong
  // scalastyle:on

  @transient private val sc = corpus.vertices.context
  @transient private val seed = new Random().nextInt()
  @transient private var innerIter = 1
  @transient private var totalTopicCounter: BDV[Count] = collectTotalTopicCounter(corpus)

  private def termVertices = corpus.vertices.filter(t => t._1 >= 0)

  private def docVertices = corpus.vertices.filter(t => t._1 < 0)

  private def checkpoint(): Unit = {
    if (innerIter % 10 == 0 && sc.getCheckpointDir.isDefined) {
      val edges = corpus.edges.map(t => t)
      edges.checkpoint()
      val newCorpus: Graph[VD, ED] = Graph.fromEdges(edges, null,
        storageLevel, storageLevel)
      corpus = updateCounter(newCorpus, numTopics).persist(storageLevel)
    }
  }

  private def collectTotalTopicCounter(graph: Graph[VD, ED]): BDV[Count] = {
    val globalTopicCounter = collectGlobalCounter(graph, numTopics)
    assert(brzSum(globalTopicCounter) == numTokens)
    globalTopicCounter
  }

  private def gibbsSampling(): Unit = {
    val sampleCorpus = sampleTokens(corpus, totalTopicCounter, innerIter + seed,
      numTokens, numTopics, numTerms, alpha, alphaAS, beta)
    sampleCorpus.persist(storageLevel)

    val counterCorpus = updateCounter(sampleCorpus, numTopics)
    counterCorpus.persist(storageLevel)
    // counterCorpus.vertices.count()
    counterCorpus.edges.count()
    totalTopicCounter = collectTotalTopicCounter(counterCorpus)

    corpus.edges.unpersist(false)
    corpus.vertices.unpersist(false)
    sampleCorpus.edges.unpersist(false)
    sampleCorpus.vertices.unpersist(false)
    corpus = counterCorpus

    checkpoint()
    innerIter += 1
  }

  def saveModel(burnInIter: Int): LDAModel = {
    var termTopicCounter: RDD[(Int, BSV[Double])] = null
    for (iter <- 1 to burnInIter) {
      logInfo(s"Save TopicModel (Iteration $iter/$burnInIter)")
      var previousTermTopicCounter = termTopicCounter
      gibbsSampling()
      val newTermTopicCounter = updateModel(termVertices)
      termTopicCounter = Option(termTopicCounter).map(_.join(newTermTopicCounter).map {
        case (term, (a, b)) =>
          val c = a + b
          c.compact()
          (term, c)
      }).getOrElse(newTermTopicCounter)

      termTopicCounter.cache().count()
      Option(previousTermTopicCounter).foreach(_.unpersist())
      previousTermTopicCounter = termTopicCounter
    }
    val model = LDAModel(numTopics, numTerms, alpha, beta)
    termTopicCounter.collect().foreach { case (term, counter) =>
      model.merge(term, counter)
    }
    model.gtc :/= burnInIter.toDouble
    model.ttc.foreach { ttc =>
      ttc :/= burnInIter.toDouble
      ttc.compact()
    }
    model
  }

  def runGibbsSampling(iterations: Int): Unit = {
    for (iter <- 1 to iterations) {
      println(s"perplexity $iter: ${perplexity()}")
      logInfo(s"Start Gibbs sampling (Iteration $iter/$iterations)")
      gibbsSampling()
    }
  }

  def mergeDuplicateTopic(threshold: Double = 0.95D): Map[Int, Int] = {
    val rows = termVertices.map(t => t._2).map { bsv =>
      val length = bsv.length
      val used = bsv.used
      val index = bsv.index.slice(0, used)
      val data = bsv.data.slice(0, used).map(_.toDouble)
      new SSV(length, index, data).asInstanceOf[SV]
    }
    val simMatrix = new RowMatrix(rows).columnSimilarities()
    val minMap = simMatrix.entries.filter { case MatrixEntry(row, column, sim) =>
      sim > threshold && row != column
    }.map { case MatrixEntry(row, column, sim) =>
      (column.toInt, row.toInt)
    }.groupByKey().map { case (topic, simTopics) =>
      (topic, simTopics.min)
    }.collect().toMap
    if (minMap.size > 0) {
      corpus = corpus.mapEdges(edges => {
        edges.attr.map { topic =>
          minMap.get(topic).getOrElse(topic)
        }
      })
      corpus = updateCounter(corpus, numTopics)
    }
    minMap
  }


  // scalastyle:off
  /**
   * 词在所有主题分布和该词所在文本的主题分布乘积: p(w)=\sum_{k}{p(k|d)*p(w|k)}=
   * \sum_{k}{\frac{{n}_{kw}+{\beta }_{w}} {{n}_{k}+\bar{\beta }} \frac{{n}_{kd}+{\alpha }_{k}} {\sum{{n}_{k}}+\bar{\alpha }}}=
   * \sum_{k} \frac{{\alpha }_{k}{\beta }_{w}  + {n}_{kw}{\alpha }_{k} + {n}_{kd}{\beta }_{w} + {n}_{kw}{n}_{kd}}{{n}_{k}+\bar{\beta }} \frac{1}{\sum{{n}_{k}}+\bar{\alpha }}}
   * \exp^{-(\sum{\log(p(w))})/N}
   * N为语料库包含的token数
   */
  // scalastyle:on
  def perplexity(): Double = {
    val totalTopicCounter = this.totalTopicCounter
    val numTopics = this.numTopics
    val numTerms = this.numTerms
    val alpha = this.alpha
    val beta = this.beta
    val totalSize = brzSum(totalTopicCounter)
    var totalProb = 0D

    // \frac{{\alpha }_{k}{\beta }_{w}}{{n}_{k}+\bar{\beta }}
    totalTopicCounter.activeIterator.foreach { case (topic, cn) =>
      totalProb += alpha * beta / (cn + numTerms * beta)
    }

    val termProb = corpus.mapVertices { (vid, counter) =>
      val probDist = BSV.zeros[Double](numTopics)
      if (vid >= 0) {
        val termTopicCounter = counter
        // \frac{{n}_{kw}{\alpha }_{k}}{{n}_{k}+\bar{\beta }}
        termTopicCounter.activeIterator.foreach { case (topic, cn) =>
          probDist(topic) = cn * alpha /
            (totalTopicCounter(topic) + numTerms * beta)
        }
      } else {
        val docTopicCounter = counter
        // \frac{{n}_{kd}{\beta }_{w}}{{n}_{k}+\bar{\beta }}
        docTopicCounter.activeIterator.foreach { case (topic, cn) =>
          probDist(topic) = cn * beta /
            (totalTopicCounter(topic) + numTerms * beta)
        }
      }
      probDist.compact()
      (counter, probDist)
    }.mapTriplets { triplet =>
      val (termTopicCounter, termProb) = triplet.srcAttr
      val (docTopicCounter, docProb) = triplet.dstAttr
      val docSize = brzSum(docTopicCounter)
      val docTermSize = triplet.attr.length

      var prob = 0D

      // \frac{{n}_{kw}{n}_{kd}}{{n}_{k}+\bar{\beta}}
      docTopicCounter.activeIterator.foreach { case (topic, cn) =>
        prob += cn * termTopicCounter(topic) /
          (totalTopicCounter(topic) + numTerms * beta)
      }
      prob += brzSum(docProb) + brzSum(termProb) + totalProb
      prob += prob / (docSize + numTopics * alpha)

      docTermSize * Math.log(prob)
    }.edges.map(t => t.attr).sum()

    math.exp(-1 * termProb / totalSize)
  }
}

object LDA {

  import LDAUtils._

  private[mllib] type DocId = VertexId
  private[mllib] type WordId = VertexId
  private[mllib] type Count = Int
  private[mllib] type ED = Array[Count]
  private[mllib] type VD = BSV[Count]

  def train(docs: RDD[(DocId, SSV)],
    numTopics: Int = 2048,
    totalIter: Int = 150,
    burnIn: Int = 5,
    alpha: Double = 0.1,
    beta: Double = 0.01,
    alphaAS: Double = 0.1): LDAModel = {
    require(totalIter > burnIn, "totalIter is less than burnIn")
    require(totalIter > 0, "totalIter is less than 0")
    require(burnIn > 0, "burnIn is less than 0")
    val topicModeling = new LDA(docs, numTopics, alpha, beta, alphaAS)
    topicModeling.runGibbsSampling(totalIter - burnIn)
    topicModeling.saveModel(burnIn)
  }

  def incrementalTrain(docs: RDD[(DocId, SSV)],
    computedModel: LDAModel,
    alphaAS: Double = 1,
    totalIter: Int = 150,
    burnIn: Int = 5): LDAModel = {
    require(totalIter > burnIn, "totalIter is less than burnIn")
    require(totalIter > 0, "totalIter is less than 0")
    require(burnIn > 0, "burnIn is less than 0")
    val numTopics = computedModel.ttc.size
    val alpha = computedModel.alpha
    val beta = computedModel.beta

    val broadcastModel = docs.context.broadcast(computedModel)
    val topicModeling = new LDA(docs, numTopics, alpha, beta, alphaAS,
      computedModel = broadcastModel)
    broadcastModel.unpersist()
    topicModeling.runGibbsSampling(totalIter - burnIn)
    topicModeling.saveModel(burnIn)
  }

  private[mllib] def sampleTokens(
    graph: Graph[VD, ED],
    totalTopicCounter: BDV[Count],
    innerIter: Long,
    numTokens: Long,
    numTopics: Int,
    numTerms: Int,
    alpha: Double,
    alphaAS: Double,
    beta: Double): Graph[VD, ED] = {
    val parts = graph.edges.partitions.size
    val broadcast = graph.edges.context.broadcast((mutable.Map[VertexId, (BSV[Double], BSV[Double])](),
      mutable.Map[VertexId, BSV[Double]]()))
    val nweGraph = graph.mapTriplets(
      (pid, iter) => {
        val rand = new XORShiftRandom(parts * innerIter + pid)
        val (wMap, dMap) = broadcast.value
        var dAS: BDV[Double] = null
        var tT: (BDV[Double], BDV[Double]) = null

        iter.map {
          triplet =>
            val termId = triplet.srcId
            val docId = triplet.dstId
            val termTopicCounter = triplet.srcAttr
            val docTopicCounter = triplet.dstAttr
            val topics = triplet.attr
            val dAS1 = -1D * (alpha * numTopics) / (numTokens - 1D + alphaAS * numTopics)
            val d1 = -1D
            var maxSampleing = 8
            while (maxSampleing > 0) {
              maxSampleing -= 1
              var i = 0
              while (i < topics.length) {
                val currentTopic = topics(i)
                val docProposal = rand.nextDouble() < 0.5
                val proposalTopic = if (docProposal) {
                  if (dAS == null) dAS = this.dAS(totalTopicCounter, alpha, alphaAS, numTokens)
                  val d = docTopicCounter.synchronized {
                    dMap.synchronized {
                      dMap.getOrElseUpdate(docId, this.d(docTopicCounter, dAS, alpha))
                    }
                  }
                  gibbsSampler(rand, d, dAS, d1, dAS1, currentTopic)
                } else {
                  if (tT == null) tT = LDA.t(totalTopicCounter, numTopics, beta)
                  val (t, t1) = tT
                  val (w, w1) = termTopicCounter.synchronized {
                    wMap.synchronized {
                      wMap.getOrElseUpdate(termId, this.w(totalTopicCounter, t,
                        termTopicCounter, numTerms, beta))
                    }
                  }
                  gibbsSampler(rand, w, t, w1(currentTopic), t1(currentTopic), currentTopic)
                }
                val newTopic = docTopicCounter.synchronized {
                  termTopicCounter.synchronized {
                    metropolisHastingsSampler(rand, docTopicCounter, termTopicCounter,
                      totalTopicCounter, beta, alpha, alphaAS, numTokens, numTerms,
                      currentTopic, proposalTopic, docProposal)
                  }
                }
                assert(newTopic < numTopics)
                if (newTopic != currentTopic) {
                  dMap.synchronized {
                    dMap -= docId
                  }
                  if (rand.nextDouble() < 0.0001) {
                    tT = null
                    dAS = null
                    dMap.synchronized {
                      wMap -= termId
                    }
                  }

                  docTopicCounter.synchronized {
                    docTopicCounter(currentTopic) -= 1
                    docTopicCounter(newTopic) += 1
                    if (docTopicCounter(currentTopic) == 0) {
                      docTopicCounter.compact()
                    }
                  }
                  termTopicCounter.synchronized {
                    termTopicCounter(currentTopic) -= 1
                    termTopicCounter(newTopic) += 1
                    if (termTopicCounter(currentTopic) == 0) {
                      termTopicCounter.compact()
                    }
                  }

                  totalTopicCounter(currentTopic) -= 1
                  totalTopicCounter(newTopic) += 1


                  topics(i) = newTopic
                }
                i += 1
              }
            }
            topics
        }
      }, TripletFields.All)
    broadcast.unpersist(false)
    nweGraph
  }

  private def updateCounter(graph: Graph[VD, ED], numTopics: Int): Graph[VD, ED] = {
    val newCounter = graph.aggregateMessages[BSV[Count]](ctx => {
      val topics = ctx.attr
      val vector = BSV.zeros[Count](numTopics)
      for (topic <- topics) {
        vector(topic) += 1
      }
      ctx.sendToDst(vector)
      ctx.sendToSrc(vector)
    }, (a, b) => {
      val c = a + b
      c.compact()
      c
    }, TripletFields.EdgeOnly)
    graph.outerJoinVertices(newCounter)((_, _, n) => n.get)
  }

  private def collectGlobalCounter(graph: Graph[VD, ED], numTopics: Int): BDV[Count] = {
    graph.vertices.filter(t => t._1 >= 0).map(_._2).
      aggregate(BDV.zeros[Count](numTopics))(_ :+= _, _ :+= _)
  }

  private def updateModel(termVertices: VertexRDD[VD]): RDD[(Int, BSV[Double])] = {
    termVertices.map(vertex => {
      val termTopicCounter = vertex._2
      val index = termTopicCounter.index.slice(0, termTopicCounter.used)
      val data = termTopicCounter.data.slice(0, termTopicCounter.used).map(_.toDouble)
      val used = termTopicCounter.used
      val length = termTopicCounter.length
      (vertex._1.toInt, new BSV[Double](index, data, used, length))
    })
  }

  private def initializeCorpus(
    docs: RDD[(LDA.DocId, SSV)],
    numTopics: Int,
    storageLevel: StorageLevel,
    computedModel: Broadcast[LDAModel] = null): Graph[VD, ED] = {
    val edges = docs.mapPartitionsWithIndex((pid, iter) => {
      val gen = new Random(pid)
      var model: LDAModel = null
      if (computedModel != null) model = computedModel.value
      iter.flatMap {
        case (docId, doc) =>
          initializeEdges(gen, new BSV[Int](doc.indices, doc.values.map(_.toInt), doc.size),
            docId, numTopics, model)
      }
    })
    var corpus: Graph[VD, ED] = Graph.fromEdges(edges, null, storageLevel, storageLevel)
    // corpus.partitionBy(PartitionStrategy.EdgePartition1D)
    corpus = updateCounter(corpus, numTopics).cache()
    corpus.vertices.count()
    corpus
  }

  private def initializeEdges(
    gen: Random,
    doc: BSV[Int],
    docId: DocId,
    numTopics: Int,
    computedModel: LDAModel = null): Array[Edge[ED]] = {
    assert(docId >= 0)
    val newDocId: DocId = -(docId + 1L)
    if (computedModel == null) {
      doc.activeIterator.map { case (term, counter) =>
        val ev = (0 until counter).map { i =>
          uniformDistSampler(gen, numTopics)
        }.toArray
        Edge(term, newDocId, ev)
      }.toArray
    }
    else {
      val tokens = computedModel.vec2Array(doc)
      val topics = new Array[Int](tokens.length)
      var docTopicCounter = computedModel.uniformDistSampler(tokens, topics, gen)
      for (t <- 0 until 15) {
        docTopicCounter = computedModel.sampleTokens(docTopicCounter,
          tokens, topics, gen)
      }
      doc.activeIterator.map { case (term, counter) =>
        val ev = topics.zipWithIndex.filter { case (topic, offset) =>
          term == tokens(offset)
        }.map(_._1)
        Edge(term, newDocId, ev)
      }.toArray
    }
  }

  // scalastyle:off
  /**
   * 这里组合使用 Gibbs sampler 和 Metropolis Hastings sampler
   * 每次采样的复杂度为 log(K) K 为主题数(应该可以优化为 (2-6)* log(KD) KD 当前文档包含主题数)
   * 1. 使用 Gibbs sampler 采样标准LDA公式中词相关部分:
   * 论文LightLDA: Big Topic Models on Modest Compute Clusters 公式(6):
   * ( \frac{{n}_{kd}^{-di}+{\beta }_{w}}{{n}_{k}^{-di}+\bar{\beta }} )
   * 2. 把第一步采样得到的概率作为 Proposal q(·) 使用 Metropolis Hastings sampler 采样非对称先验公式
   * 论文 Rethinking LDA: Why Priors Matter 公式(3)
   * \frac{{n}_{kw}^{-di}+{\beta }_{w}}{{n}_{k}^{-di}+\bar{\beta}} \frac{{n}_{kd} ^{-di}+ \bar{\alpha} \frac{{n}_{k}^{-di} + \acute{\alpha}}{\sum{n}_{k} +\bar{\acute{\alpha}}}}{\sum{n}_{kd}^{-di} +\bar{\alpha}}
   *
   * 其中
   * \bar{\beta}=\sum_{w}{\beta}_{w}
   * \bar{\alpha}=\sum_{k}{\alpha}_{k}
   * \bar{\acute{\alpha}}=\bar{\acute{\alpha}}=\sum_{k}\acute{\alpha}
   * {n}_{kd} 是文档d中主题为k的tokens数
   * {n}_{kw} 词中主题为k的tokens数
   * {n}_{k} 是语料库中主题为k的tokens数
   */
  def metropolisHastingsSampler(
    rand: Random,
    docTopicCounter: VD,
    termTopicCounter: VD,
    totalTopicCounter: BDV[Count],
    beta: Double,
    alpha: Double,
    alphaAS: Double,
    numTokens: Double,
    numTerms: Double,
    currentTopic: Int,
    newTopic: Int,
    docProposal: Boolean): Int = {
    if (newTopic == currentTopic) return newTopic

    val ctp = tokenTopicProb(docTopicCounter, termTopicCounter, totalTopicCounter,
      beta, alpha, alphaAS, numTokens, numTerms, currentTopic, true)
    val ntp = tokenTopicProb(docTopicCounter, termTopicCounter, totalTopicCounter,
      beta, alpha, alphaAS, numTokens, numTerms, newTopic, false)
    val cwp = if (docProposal) {
      docTopicProb(docTopicCounter, totalTopicCounter, currentTopic,
        alpha, alphaAS, numTokens, true)
    } else {
      wordTopicProb(termTopicCounter, totalTopicCounter, currentTopic,
        numTerms, beta, true)
    }
    val nwp = if (docProposal) {
      docTopicProb(docTopicCounter, totalTopicCounter, newTopic, alpha,
        alphaAS, numTokens, false)
    } else {
      wordTopicProb(termTopicCounter, totalTopicCounter, newTopic,
        numTerms, beta, false)
    }
    val pi = (ntp * cwp) / (ctp * nwp)

    if (rand.nextDouble() < 1e-6) {
      println(s"Pi: $docProposal ${pi}")
      println(s"($ntp * $cwp) / ($ctp * $nwp) ")
    }

    if (rand.nextDouble() < math.min(1.0, pi)) {
      newTopic
    } else {
      currentTopic
    }
  }

  // scalastyle:on

  // scalastyle:off
  @inline private def tokenTopicProb(
    docTopicCounter: VD,
    termTopicCounter: VD,
    totalTopicCounter: BDV[Count],
    beta: Double,
    alpha: Double,
    alphaR: Double,
    numTokens: Double,
    numTerms: Double,
    topic: Int,
    isAdjustment: Boolean): Double = {
    val numTopics = docTopicCounter.length
    val adjustment = if (isAdjustment) -1 else 0
    val ratio = (totalTopicCounter(topic) + adjustment + alphaR) /
      (numTokens - 1 + alphaR * numTopics)
    val asPrior = ratio * (alpha * numTopics)
    // 这里移除了常数项 (docLen - 1 + alpha * numTopics)
    (termTopicCounter(topic) + adjustment + beta) *
      (docTopicCounter(topic) + adjustment + asPrior) /
      (totalTopicCounter(topic) + adjustment + (numTerms * beta))

    // 原始公式论文 Rethinking LDA: Why Priors Matter 公式(3)
    // val docLen = brzSum(docTopicCounter)
    // (termTopicCounter(topic) + adjustment + beta) * (docTopicCounter(topic) + adjustment + asPrior) /
    //   ((totalTopicCounter(topic) + adjustment + (numTerms * beta)) * (docLen - 1 + alpha * numTopics))
  }

  // scalastyle:on

  @inline private def wordTopicProb(
    termTopicCounter: VD,
    totalTopicCounter: BDV[Count],
    topic: Int,
    numTerms: Double,
    beta: Double,
    isAdjustment: Boolean): Double = {
    val termSum = beta * numTerms
    val count = termTopicCounter(topic)
    val adjustment = if (isAdjustment) -1.0 else 0.0
    (count + adjustment + beta) / (totalTopicCounter(topic) + adjustment + termSum)
  }

  @inline private def docTopicProb(
    docTopicCounter: VD,
    totalTopicCounter: BDV[Count],
    topic: Int,
    alpha: Double,
    alphaR: Double,
    numTokens: Double,
    isAdjustment: Boolean): Double = {
    val numTopics = docTopicCounter.length
    val termSum = numTokens - 1 + alphaR * numTopics
    val alphaSum = alpha * numTopics
    if (isAdjustment) {
      val ratio = (totalTopicCounter(topic) - 1 + alphaR) / termSum
      val as = ratio * alphaSum
      docTopicCounter(topic) + as - 1
    } else {
      val ratio = (totalTopicCounter(topic) + alphaR) / termSum
      val as = ratio * alphaSum
      docTopicCounter(topic) + as
    }
  }

  private def gibbsSampler(
    rand: Random,
    sv: BSV[Double],
    dv: BDV[Double],
    adjustmentSV: Double,
    adjustmentDV: Double,
    currentTopic: Int): Int = {
    // 搜索稀疏向量
    val numTopics = sv.length
    val used = sv.used
    val data = sv.data
    val index = sv.index

    val adjustment = adjustmentDV + adjustmentSV
    val lastSum = data(used - 1) - dv(index(used - 1)) + dv(numTopics - 1) + adjustment
    var distSum = rand.nextDouble() * lastSum

    val fun = (i: Int) => if (index(i) >= currentTopic) data(i) + adjustment else data(i)
    val pos = binarySearchInterval(fun, distSum, 0, used, true)

    if (pos < used && distSum == data(pos)) return index(pos)
    if (pos == 0) {
      val a = if (index.head >= currentTopic) adjustmentDV else 0.0
      val withOutPos = dv(index.head) + a
      if (withOutPos <= distSum) return index.head
    }
    else if (pos < used) {
      val a = if (index(pos - 1) >= currentTopic) adjustment else 0.0
      val withOutPos = data(pos - 1) - dv(index(pos - 1)) + dv(index(pos)) + a
      if (withOutPos <= distSum) return index(pos)
    }

    var b = 0
    var e = numTopics
    if (pos == 0) {
      e = index.head + 1
    } else if (pos == used) {
      b = index(used - 1)
      val sumW = data(used - 1) - dv(b) + adjustmentSV
      distSum = distSum - sumW
    } else if (index(pos - 1) >= currentTopic) {
      b = index(pos - 1)
      e = index(pos) + 1
      val sumW = data(pos - 1) - dv(b) + adjustmentSV
      distSum = distSum - sumW
    } else {
      b = index(pos - 1)
      e = index(pos) + 1
      val sumW = data(pos - 1) - dv(b)
      distSum = distSum - sumW
    }
    // 搜索稠密向量
    val dvFun = (i: Int) => if (i >= currentTopic) dv.data(i) + adjustmentDV else dv.data(i)
    val topic = binarySearchInterval(dvFun, distSum, b, e, true)
    math.min(topic, numTopics - 1)
  }

  /**
   * \frac{{n}_{kw}}{{n}_{k}+\bar{\beta}}
   */
  @inline private def w(
    totalTopicCounter: BDV[Count],
    t: BDV[Double],
    termTopicCounter: VD,
    numTerms: Int,
    beta: Double): (BSV[Double], BSV[Double]) = {
    val numTopics = termTopicCounter.length
    val termSum = beta * numTerms
    val used = termTopicCounter.used
    val index = termTopicCounter.index.slice(0, used)
    val data = termTopicCounter.data
    val w = new Array[Double](used)
    val w1 = new Array[Double](used)

    var lastSum = 0D
    var i = 0

    while (i < used) {
      val topic = index(i)
      val count = data(i)
      val lastW = count / (totalTopicCounter(topic) + termSum)
      val lastW1 = (count - 1D) / (totalTopicCounter(topic) - 1D + termSum)
      lastSum += lastW
      w(i) = lastSum + t(topic)
      w1(i) = lastW1 - lastW
      i += 1
    }
    (new BSV[Double](index, w, used, numTopics),
      new BSV[Double](index, w1, used, numTopics))
  }

  @inline private def dAS(
    totalTopicCounter: BDV[Count],
    alpha: Double,
    alphaAS: Double,
    numTokens: Double): BDV[Double] = {
    val numTopics = totalTopicCounter.length
    val asPrior = BDV.zeros[Double](numTopics)
    val termSum = numTokens - 1D + alphaAS * numTopics
    val alphaSum = alpha * numTopics
    var lastSum = 0.0
    for (topic <- 0 until numTopics) {
      val lastA = alphaSum * (totalTopicCounter(topic) + alphaAS) / termSum
      lastSum += lastA
      asPrior(topic) = lastSum
    }
    asPrior
  }

  @inline private def d(
    docTopicCounter: VD,
    dAS: BDV[Double],
    alpha: Double): BSV[Double] = {
    val numTopics = docTopicCounter.length
    val used = docTopicCounter.used
    val index = docTopicCounter.index.slice(0, used)
    val data = docTopicCounter.data
    val d = new Array[Double](used)

    var lastSum = 0D
    var i = 0
    while (i < used) {
      val topic = index(i)
      val lastD = data(i)
      lastSum += lastD
      d(i) = lastSum + dAS(topic)
      i += 1
    }
    new BSV[Double](index, d, used, numTopics)
  }

  /**
   * \frac{{\beta}_{w}}{{n}_{k}+\bar{\beta}}
   */
  private def t(
    totalTopicCounter: BDV[Count],
    numTerms: Int,
    beta: Double): (BDV[Double], BDV[Double]) = {
    val numTopics = totalTopicCounter.length
    val t = BDV.zeros[Double](numTopics)
    val t1 = BDV.zeros[Double](numTopics)

    val termSum = beta * numTerms

    var lastTsum = 0D
    for (topic <- 0 until numTopics) {
      val lastT = beta / (totalTopicCounter(topic) + termSum)
      val lastT1 = beta / (totalTopicCounter(topic) - 1.0 + termSum)
      lastTsum += lastT
      t(topic) = lastTsum
      t1(topic) = lastT1 - lastT
    }
    (t, t1)
  }
}

object LDAUtils {

  /**
   * A uniform distribution sampler
   */
  @inline private[mllib] def uniformDistSampler(rand: Random, dimension: Int): Int = {
    rand.nextInt(dimension)
  }

  def binarySearchArray[K](
    index: Array[K],
    key: K,
    begin: Int,
    end: Int,
    greater: Boolean)(implicit ord: Ordering[K], ctag: ClassTag[K]): Int = {
    binarySearchInterval(i => index(i), key, begin, end, greater)
  }

  def binarySearchInterval[K](
    index: Int => K,
    key: K,
    begin: Int,
    end: Int,
    greater: Boolean)(implicit ord: Ordering[K], ctag: ClassTag[K]): Int = {
    if (begin == end) {
      return if (greater) end else begin - 1
    }
    var b = begin
    var e = end - 1

    var mid: Int = (e + b) >> 1
    while (b <= e) {
      mid = (e + b) >> 1
      val v = index(mid)
      if (ord.lt(v, key)) {
        b = mid + 1
      }
      else if (ord.gt(v, key)) {
        e = mid - 1
      }
      else {
        return mid
      }
    }
    val v = index(mid)
    mid = if ((greater && ord.gteq(v, key)) || (!greater && ord.lteq(v, key))) {
      mid
    }
    else if (greater) {
      mid + 1
    }
    else {
      mid - 1
    }
    //  if (greater) {
    //    if (mid < end) assert(ord.gteq(index(mid), key))
    //    if (mid > 0) assert(ord.lteq(index(mid - 1), key))
    //  } else {
    //    if (mid > 0) assert(ord.lteq(index(mid), key))
    //    if (mid < end - 1) assert(ord.gteq(index(mid + 1), key))
    //  }
    mid
  }

  @inline private[mllib] def binarySearchSparseVector(index: Int, w: BSV[Double]) = {
    val pos = binarySearchArray(w.index, index, 0, w.used, false)
    if (pos > -1) {
      w.data(pos)
    }
    else {
      0D
    }
  }
}

class LDAKryoRegistrator extends KryoRegistrator {
  def registerClasses(kryo: com.esotericsoftware.kryo.Kryo) {
    val gkr = new GraphKryoRegistrator
    gkr.registerClasses(kryo)

    kryo.register(classOf[BSV[LDA.Count]])
    kryo.register(classOf[BSV[Double]])

    kryo.register(classOf[BDV[LDA.Count]])
    kryo.register(classOf[BDV[Double]])

    kryo.register(classOf[SV])
    kryo.register(classOf[SSV])
    kryo.register(classOf[SDV])

    kryo.register(classOf[LDA.ED])
    kryo.register(classOf[LDA.VD])

    kryo.register(classOf[Random])
    kryo.register(classOf[LDA])
    kryo.register(classOf[LDAModel])
  }
}
