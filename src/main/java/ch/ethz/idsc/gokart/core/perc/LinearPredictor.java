// code by vc
package ch.ethz.idsc.gokart.core.perc;

import ch.ethz.idsc.tensor.RealScalar;
import ch.ethz.idsc.tensor.Tensor;
import ch.ethz.idsc.tensor.Tensors;
import ch.ethz.idsc.tensor.alg.Flatten;
import ch.ethz.idsc.tensor.alg.Last;
import ch.ethz.idsc.tensor.alg.Transpose;
import ch.ethz.idsc.tensor.mat.Inverse;
import ch.ethz.idsc.tensor.red.Mean;

public class LinearPredictor {
  private Tensor nextMeans = Tensors.empty();
  private Tensor nextHulls = Tensors.empty();
  private double step;
  private Tensor twiceNextMeans = Tensors.empty();// prediction 2 time steps in the future
  private Tensor twiceNextHulls = Tensors.empty();

  public LinearPredictor(ClusterCollection collection, double step) {
    this.step = step;
    for (ClusterDeque clusterDeque : collection.getCollection()) {
      Tensor nonEmptyMeans = clusterDeque.getNonEmptyMeans();
      Tensor hull = clusterDeque.getLast().hull();
      predictNextMean(nonEmptyMeans);
      if (Tensors.nonEmpty(nextMeans) && Tensors.nonEmpty(twiceNextMeans))
        predictNextHull(hull);
    }
  }

  private void predictNextMean(Tensor nonEmptyMeans) {
    Tensor predictMean = predictMean(nonEmptyMeans, 1);
    if (Tensors.nonEmpty(predictMean)) {
      nextMeans.append(predictMean);
      twiceNextMeans.append(predictMean(nonEmptyMeans, 2));
    }
  }

  // linear prediction: linear regression in closed form, from the last mean take a step of length i*step
  private Tensor predictMean(Tensor nonEmptyMeans, int i) {
    Tensor x = Tensor.of(nonEmptyMeans.stream().map(tensor -> Tensors.of(tensor.Get(0), RealScalar.of(1))));// homogeneous coordinates
    Tensor y = Tensor.of(nonEmptyMeans.stream().map(tensor -> tensor.Get(1)));
    if (Tensors.nonEmpty(x)) {
      if (x.length() > 1) { // fit a line if more than one point
        Tensor beta = Inverse.of(Transpose.of(x).dot(x)).dot(Transpose.of(x).dot(y));
        double b = beta.Get(0).number().doubleValue();
        double nextX = Last.of(nonEmptyMeans).Get(0).number().doubleValue() + i * step * Math.cos(b);
        double nextY = Last.of(nonEmptyMeans).Get(1).number().doubleValue() + i * step * Math.sin(b);
        return Tensors.vectorDouble(nextX, nextY);
      }
      return Flatten.of(nonEmptyMeans);// if only one point assume it is not going to move
    }
    return Tensors.empty();
  }

  // assign last hull shape to the new mean
  private void predictNextHull(Tensor hull) {
    if (Tensors.nonEmpty(hull)) {
      Tensor mean = Mean.of(hull);
      Tensor next = Tensors.empty();
      Tensor twiceNext = Tensors.empty();
      Tensor nm = Last.of(nextMeans);
      Tensor tnm = Last.of(twiceNextMeans);
      if (Tensors.nonEmpty(Last.of(nm))) {
        for (Tensor x : hull) {
          Tensor y = x.subtract(mean);
          next.append(nm.add(y));
          twiceNext.append(tnm.add(y));
        }
        nextHulls.append(next);
        twiceNextHulls.append(twiceNext);
      }
    }
  }

  public Tensor getMeanPredictions() {
    return nextMeans.unmodifiable();
  }

  public Tensor getHullPredictions() {
    return nextHulls.unmodifiable();
  }

  public Tensor getTNMeanPredictions() {
    return twiceNextMeans.unmodifiable();
  }

  public Tensor getTNHullPredictions() {
    return twiceNextHulls.unmodifiable();
  }
}
