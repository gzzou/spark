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

package org.apache.spark.mllib.ann

import breeze.linalg.{DenseVector => BDV, SparseVector => BSV}
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.optimization._
import org.apache.spark.mllib.linalg.{Vectors, Vector}
import breeze.linalg.DenseVector
import breeze.linalg.{DenseVector => BDV}
import breeze.linalg.{SparseVector => BSV}

/**
 * :: DeveloperApi ::
 * GeneralizedModel represents a model trained using
 * GeneralizedAlgorithm.
 *
 * @param weights Weights computed for every feature.
 */
@DeveloperApi
abstract class GeneralizedModel(val weights: Vector )

  extends Serializable {

  /**
   * Predict the result given a data point and the weights learned.
   *
   * @param dataMatrix Row vector containing the features for this data point
   * @param weightMatrix Column vector containing the weights of the model
   *
   * If the prediction model consists of a multi-dimensional vector, predictPoint
   * returns only the first element of each vector. To get the whole vector,
   * use predictPointV instead.
   */
  protected def predictPoint( dataMatrix: Vector, weightMatrix: Vector ): Double

  /**
   * Predict the result given a data point and the weights learned.
   *
   * @param dataMatrix Row vector containing the features for this data point
   * @param weightMatrix Column vector containing the weights of the model
   *
   * Returns the complete output vector.
   */
  protected def predictPointV( dataMatrix: Vector, weightsMatrix: Vector ): Vector

  /**
   * Predict values for the given data set using the model trained.
   *
   * @param testData RDD representing data points to be predicted
   * @return RDD[Double] where each entry contains the corresponding prediction
   *
   * Returns only first element of output vector.
   */
  def predict( testData: RDD[Vector] ): RDD[Double] = {

    val localWeights = weights
    testData.map(v => predictPoint(v, localWeights ) )

  }

  /**
   * Predict values for the given data set using the model trained.
   *
   * @param testData RDD representing data points to be predicted
   * @return RDD[Vector] where each entry contains the corresponding prediction
   *
   * Returns the complete output vector.
   */
  def predictV( testData: RDD[Vector] ): RDD[Vector] = {

    val localWeights = weights
    testData.map( v => predictPointV( v, localWeights ) )

  }

  /**
   * Predict values for a single data point using the model trained.
   *
   * @param testData array representing a single data point
   * @return Double prediction from the trained model
   *
   * Returns only first element of output vector.
   */
  def predict( testData: Vector ): Double = {

    predictPoint( testData, weights )

  }

  /**
   * Predict values for a single data point using the model trained.
   *
   * @param testData array representing a single data point
   * @return Vector prediction from the trained model
   *
   * Returns the complete vector.
   */
  def predictV( testData: Vector ): Vector = {

    predictPointV( testData, weights )

  }

}

/**
 * :: DeveloperApi ::
 * GeneralizedAlgorithm implements methods to train a function.
 * This class should be extended with an Optimizer to create a new GM.
 */
@DeveloperApi
abstract class GeneralizedAlgorithm[M <: GeneralizedModel]
  extends Logging with Serializable {

  /** The optimizer to solve the problem. */
  def optimizer: Optimizer

  /**
   * Create a model given the weights
   */
  protected def createModel(weights: Vector): M

  /**
   * Run the algorithm with the configured parameters on an input RDD
   * of (Vector,Vector) entries starting from the initial weights provided.
   */
  def run(input: RDD[(Vector,Vector)], initialWeights: Vector): M = {

    val data = input.map( v => (
      (0.0).toDouble,
      Vectors.fromBreeze( DenseVector.vertcat(
        v._1.toBreeze.toDenseVector,
        v._2.toBreeze.toDenseVector ) )
      ) )
    val weights = optimizer.optimize(data, initialWeights)

    createModel( weights )

  }
}