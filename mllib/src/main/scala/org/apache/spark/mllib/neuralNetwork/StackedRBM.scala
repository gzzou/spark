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

package org.apache.spark.mllib.neuralNetwork

import java.util.Random

import scala.collection.JavaConversions._

import breeze.linalg.{DenseVector => BDV, DenseMatrix => BDM, sum => brzSum}
import breeze.numerics.{sigmoid => brzSigmoid}

import org.apache.spark.broadcast.Broadcast
import org.apache.spark.Logging
import org.apache.spark.mllib.linalg.{Vector => SV, DenseVector => SDV}
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.util.random.XORShiftRandom
import org.apache.spark.rdd.RDD

class StackedRBM(val innerRBMs: Array[RBM])
  extends Logging with Serializable {
  def this(topology: Array[Int]) {
    this(StackedRBM.initializeRBMs(topology))
  }

  def numLayer = innerRBMs.length

  def numInput = innerRBMs.head.numVisible

  def numOut = innerRBMs.last.numHidden

  def activateHidden(visible: BDM[Double], toLayer: Int): BDM[Double] = {
    var x = visible
    for (layer <- 0 until toLayer) {
      x = innerRBMs(layer).activateHidden(x)
      // x = innerRBMs(layer).bernoulli(x)
    }
    x
  }

  def activateHidden(visible: BDM[Double]): BDM[Double] = {
    activateHidden(visible, numLayer)
  }
}

object StackedRBM {
  def train(
    data: RDD[SV],
    batchSize: Int,
    numIteration: Int,
    topology: Array[Int],
    fraction: Double,
    momentum: Double,
    weightCost: Double,
    learningRate: Double): StackedRBM = {
    train(data, batchSize, numIteration, new StackedRBM(topology),
      fraction, momentum, weightCost, learningRate)
  }


  def train(
    data: RDD[SV],
    batchSize: Int,
    numIteration: Int,
    stackedRBM: StackedRBM,
    fraction: Double,
    momentum: Double,
    weightCost: Double,
    learningRate: Double,
    maxLayer: Int = -1): StackedRBM = {
    val sc = data.context
    val numInput = stackedRBM.numInput
    val trainLayer = if (maxLayer > -1D) {
      maxLayer
    } else {
      stackedRBM.numLayer
    }

    for (layer <- 0 until trainLayer) {
      println(s"Train ($layer/$trainLayer)")
      val sampleData = sample(data, fraction)
      val broadcastStackedRBM = sc.broadcast(stackedRBM)
      val dataBatch = batches(sampleData, broadcastStackedRBM, batchSize, numInput, layer)
      val rbm = stackedRBM.innerRBMs(layer)
      RBM.train(dataBatch, batchSize, numIteration,
        rbm, fraction, momentum, weightCost, learningRate)
      broadcastStackedRBM.destroy(blocking = false)
    }

    stackedRBM
  }


  private[mllib] def sample[T](data: RDD[T], fraction: Double): RDD[T] = {
    if (fraction <= 1.0D && fraction > 0D) {
      data.sample(false, fraction)
    } else {
      data
    }
  }

  private[mllib] def batches(
    data: RDD[SV],
    broadcastStackedRBM: Broadcast[StackedRBM],
    batchSize: Int,
    numInput: Int,
    toLayer: Int
    ): RDD[SV] = {
    val dataBatch = data.mapPartitions { itr =>
      val stackedRBM = broadcastStackedRBM.value
      itr.grouped(batchSize).flatMap { seq =>
        var x = BDM.zeros[Double](numInput, seq.size)
        seq.zipWithIndex.foreach { case (v, i) =>
          x(::, i) :+= v.toBreeze
        }
        x = stackedRBM.activateHidden(x, toLayer)
        (0 until seq.size).map { i =>
          Vectors.fromBreeze(x(::, i))
        }
      }

    }
    dataBatch
  }

  def initializeRBMs(topology: Array[Int]): Array[RBM] = {
    val numLayer = topology.length - 1
    val innerRBMs: Array[RBM] = new Array[RBM](numLayer)
    for (layer <- 0 until numLayer) {
      innerRBMs(layer) = new RBM(topology(layer), topology(layer + 1))
      println(s"innerRBMs($layer) = ${innerRBMs(layer).numVisible} * ${innerRBMs(layer).numHidden}")
    }
    innerRBMs
  }
}
