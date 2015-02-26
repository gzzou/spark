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

package org.apache.spark.mllib.ann2

import breeze.linalg.
{DenseMatrix => BDM, Vector => BV, DenseVector => BDV, sum => Bsum, axpy => brzAxpy, *}
import breeze.numerics.{sigmoid => Bsigmoid}

import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.mllib.optimization.{Updater, LBFGS, Gradient}
import org.apache.spark.rdd.RDD
import org.apache.spark.util.random.XORShiftRandom

trait Layer extends Serializable {
  def getInstance(weights: Vector, position: Int): LayerModel
  def getInstance(seed: Long): LayerModel
}

trait LayerModel extends Serializable {
  val size: Int
  def eval(data: BDM[Double]): BDM[Double]
  def prevDelta(nextDelta: BDM[Double], input: BDM[Double]): BDM[Double]
  def grad(delta: BDM[Double], input: BDM[Double]): Vector
  def weights(): Vector
}

class AffineLayer(numIn: Int, numOut: Int) extends Layer {
  override def getInstance(weights: Vector, position: Int): LayerModel = {
    val (w, b) = AffineLayerModel.unroll(weights, position, numIn, numOut)
    AffineLayerModel(w, b)
  }

  override def getInstance(seed: Long = 11L): LayerModel = {
    val (w, b) = AffineLayerModel.randomWeights(numIn, numOut, seed)
    AffineLayerModel(w, b)
  }
}

class AffineLayerModel private(w: BDM[Double], b: BDV[Double]) extends LayerModel {
  val size = w.size + b.length

  override def eval(data: BDM[Double]): BDM[Double] = {
    val output = w * data
    output(::, *) :+= b
    output
  }

  override def prevDelta(nextDelta: BDM[Double], input: BDM[Double]): BDM[Double] = w.t * nextDelta

  override def grad(delta: BDM[Double], input: BDM[Double]): Vector = {
    val g = delta * input.t
    g :/= input.cols.toDouble
    val avgDelta = Bsum(delta(*, ::))
    avgDelta :/= input.cols.toDouble
    AffineLayerModel.roll(g, avgDelta)
  }

  override def weights(): Vector = AffineLayerModel.roll(w, b)
}

object AffineLayerModel {

  def apply(w: BDM[Double], b: BDV[Double]): AffineLayerModel = {
    new AffineLayerModel(w, b)
  }

  def unroll(weights: Vector, position: Int,
             numIn: Int, numOut: Int): (BDM[Double], BDV[Double]) = {
    val weightsCopy = weights.toArray
    val w = new BDM[Double](numOut, numIn, weightsCopy, position)
    val b = new BDV[Double](weightsCopy, position + (numOut * numIn), 1, numOut)
    (w, b)
  }

  def roll(w: BDM[Double], b: BDV[Double]): Vector = {
    val result = new Array[Double](w.size + b.length)
    System.arraycopy(w.data, 0, result, 0, w.size)
    System.arraycopy(b.data, 0, result, w.size, b.length)
    Vectors.dense(result)
  }

  def randomWeights(numIn: Int, numOut: Int, seed: Long = 11L): (BDM[Double], BDV[Double]) = {
    val rand: XORShiftRandom = new XORShiftRandom(seed)
    val weights = BDM.fill[Double](numOut, numIn){ (rand.nextDouble * 4.8 - 2.4) / numIn }
    val bias = BDV.fill[Double](numOut){ (rand.nextDouble * 4.8 - 2.4) / numIn }
    (weights, bias)
  }
}

object ANNFunctions {

  def Sigmoid(data: BDM[Double]): BDM[Double] = Bsigmoid(data)

  def SigmoidDerivative(data: BDM[Double]): BDM[Double] = {
    val derivative = BDM.ones[Double](data.rows, data.cols)
    derivative :-= data
    derivative :*= data
    derivative
  }
}

class FunctionalLayer (activationFunction: BDM[Double] => BDM[Double],
                       activationDerivative: BDM[Double] => BDM[Double]) extends Layer {
  override def getInstance(weights: Vector, position: Int): LayerModel = getInstance(0L)

  override def getInstance(seed: Long): LayerModel =
    FunctionalLayerModel(activationFunction, activationDerivative)
}

class FunctionalLayerModel private (activationFunction: BDM[Double] => BDM[Double],
                                    activationDerivative: BDM[Double] => BDM[Double]
                                     ) extends LayerModel {
  val size = 0

  override def eval(data: BDM[Double]): BDM[Double] = activationFunction(data)

  override def prevDelta(nextDelta: BDM[Double], input: BDM[Double]): BDM[Double] =
    nextDelta :* activationDerivative(input)

  override def grad(delta: BDM[Double], input: BDM[Double]): Vector =
    Vectors.dense(new Array[Double](0))

  override def weights(): Vector = Vectors.dense(new Array[Double](0))
}

object FunctionalLayerModel {
  def apply(activationFunction: BDM[Double] => BDM[Double],
            activationDerivative: BDM[Double] => BDM[Double]): FunctionalLayerModel = {
    new FunctionalLayerModel(activationFunction, activationDerivative)
  }
}

class Topology(val layers: Array[Layer]) extends Serializable {

}

object Topology {
  def multiLayerPerceptron(layerSizes: Array[Int]): Topology = {
    val layers = new Array[Layer]((layerSizes.length - 1) * 2)
    for(i <- 0 until layerSizes.length - 1){
      layers(i * 2) = new AffineLayer(layerSizes(i), layerSizes(i + 1))
      layers(i * 2 + 1) = new FunctionalLayer(ANNFunctions.Sigmoid, ANNFunctions.SigmoidDerivative)
    }
    new Topology(layers)
  }
}

class FeedForwardModel(val layerModels: Array[LayerModel]) extends Serializable {
  def forward(data: BDM[Double]): Array[BDM[Double]] = {
    val outputs = new Array[BDM[Double]](layerModels.length)
    outputs(0) = layerModels(0).eval(data)
    for(i <- 1 until layerModels.length){
      outputs(i) = layerModels(i).eval(outputs(i-1))
    }
    outputs
  }

  def computeGradient(data: BDM[Double], target: BDM[Double], cumGradient: Vector,
                      realBatchSize: Int): Double = {
    val outputs = forward(data)
    val deltas = new Array[BDM[Double]](layerModels.length)
    val error = target - outputs.last
    val L = layerModels.length - 1
    // TODO: make the following not so ugly
    // if last two layers form an affine + function layer == sigmoid or softmax
    if (layerModels(L).size == 0 && layerModels(L - 1).size > 0) {
      deltas(L) = error
      deltas(L - 1) = layerModels(L).prevDelta(error, outputs(L - 1))
    } else {
      assert(false)
    }
    for (i <- (L - 2) to (0, -1)) {
      deltas(i) = layerModels(i + 1).prevDelta(deltas(i + 1), outputs(i))
    }
    val grads = new Array[Vector](layerModels.length)
    for (i <- 0 until layerModels.length) {
      val input = if (i==0) data else outputs(i)
      grads(i) = layerModels(i).grad(deltas(i), input)
    }
    // update cumGradient
    val cumGradientArray = cumGradient.toArray
    var offset = 0
    // TODO: extract roll
    for (i <- 0 until grads.length) {
      val gradArray = grads(i).toArray
      var k = 0
      while (k < gradArray.length) {
        cumGradientArray(offset + k) += gradArray(k)
        k += 1
      }
      offset += gradArray.length
    }

    val outerError = Bsum(error :* error) / 2
    /* NB! dividing by the number of instances in
     * the batch to be transparent for the optimizer */
    outerError / realBatchSize
  }

  def weights(): Vector = {
    // TODO: extract roll
    var size = 0
    for(i <- 0 until layerModels.length) {
      size += layerModels(i).size
    }
    val array = new Array[Double](size)
    var offset = 0
    for(i <- 0 until layerModels.length) {
      val layerWeights = layerModels(i).weights().toArray
      System.arraycopy(layerWeights, 0, array, offset, layerWeights.length)
      offset += layerWeights.length
    }
    Vectors.dense(array)
  }

  def predict(data: Vector): Vector = {
    val result = forward(data.toBreeze.toDenseVector.toDenseMatrix.t)
    Vectors.dense(result.last.toArray)
  }

}

object FeedForwardModel {
  def apply(topology: Topology, weights: Vector): FeedForwardModel = {
    val layers = topology.layers
    val layerModels = new Array[LayerModel](layers.length)
    var offset = 0
    for(i <- 0 until layers.length){
      layerModels(i) = layers(i).getInstance(weights, offset)
      offset += layerModels(i).size
    }
    new FeedForwardModel(layerModels)
  }

  def apply(topology: Topology, seed: Long = 11L): FeedForwardModel = {
    val layers = topology.layers
    val layerModels = new Array[LayerModel](layers.length)
    var offset = 0
    for(i <- 0 until layers.length){
      layerModels(i) = layers(i).getInstance(seed)
      offset += layerModels(i).size
    }
    new FeedForwardModel(layerModels)
  }

}

class ANNGradient(topology: Topology, dataStacker: DataStacker) extends Gradient {

  override def compute(data: Vector, label: Double, weights: Vector): (Vector, Double) = {
    val gradient = Vectors.zeros(weights.size)
    val loss = compute(data, label, weights, gradient)
    (gradient, loss)
  }

  override def compute(data: Vector, label: Double, weights: Vector,
                       cumGradient: Vector): Double = {
    val (input, target, realBatchSize) = dataStacker.unstack(data)
    val model = FeedForwardModel(topology, weights)
    model.computeGradient(input, target, cumGradient, realBatchSize)
  }
}

class DataStacker(batchSize: Int, inputSize: Int, outputSize: Int) extends Serializable {
  def stack(data: RDD[(Vector, Vector)]): RDD[(Double, Vector)] = {
    val stackedData = if (batchSize == 1) {
      data.map(v =>
        (0.0,
          Vectors.fromBreeze(BDV.vertcat(
            v._1.toBreeze.toDenseVector,
            v._2.toBreeze.toDenseVector))
          ))
    } else {
      data.mapPartitions { it =>
        it.grouped(batchSize).map { seq =>
          val size = seq.size
          val bigVector = new Array[Double](inputSize * size + outputSize * size)
          var i = 0
          seq.foreach { case (in, out) =>
            System.arraycopy(in.toArray, 0, bigVector, i * inputSize, inputSize)
            System.arraycopy(out.toArray, 0, bigVector,
              inputSize * size + i * outputSize, outputSize)
            i += 1
          }
          (0.0, Vectors.dense(bigVector))
        }
      }
    }
    stackedData
  }

  def unstack(data: Vector): (BDM[Double], BDM[Double], Int) = {
    val arrData = data.toArray
    val realBatchSize = arrData.length / (inputSize + outputSize)
    val input = new BDM(inputSize, realBatchSize, arrData)
    val target = new BDM(outputSize, realBatchSize, arrData, inputSize * realBatchSize)
    (input, target, realBatchSize)
  }
}

private class ANNUpdater extends Updater {

  override def compute(weightsOld: Vector,
                       gradient: Vector,
                       stepSize: Double,
                       iter: Int,
                       regParam: Double): (Vector, Double) = {
    val thisIterStepSize = stepSize
    val brzWeights: BV[Double] = weightsOld.toBreeze.toDenseVector
    brzAxpy(-thisIterStepSize, gradient.toBreeze, brzWeights)
    (Vectors.fromBreeze(brzWeights), 0)
  }
}
/* MLlib-style trainer class that trains a network given the data and topology
* */
class FeedForwardNetwork private[mllib](topology: Topology, maxNumIterations: Int,
                                        convergenceTol: Double, inputSize: Int, outputSize: Int,
                                        batchSize: Int = 1) extends Serializable {

  private val dataStacker = new DataStacker(batchSize, inputSize, outputSize)
  private val gradient =
    new ANNGradient(topology, dataStacker)
  private val updater = new ANNUpdater()
  private val optimizer = new LBFGS(gradient, updater).
    setConvergenceTol(convergenceTol).setNumIterations(maxNumIterations)

  private def run(data: RDD[(Vector, Vector)],
                  initialWeights: Vector): FeedForwardModel = {

    val weights = optimizer.optimize(dataStacker.stack(data), initialWeights)
    FeedForwardModel(topology, weights)
  }
}

/* MLlib-style object for the collection of train methods
 *
 */
object FeedForwardNetwork {

  def train(trainingRDD: RDD[(Vector, Vector)],
            batchSize: Int,
            maxIterations: Int,
            topology: Topology,
            initialWeights: Vector) = {
    val dataSample = trainingRDD.first()
    val inputSize = dataSample._1.size
    val outputSize = dataSample._2.size
    new FeedForwardNetwork(topology, maxIterations, 1e-4, inputSize, outputSize, batchSize).
      run(trainingRDD, initialWeights)
  }
}
