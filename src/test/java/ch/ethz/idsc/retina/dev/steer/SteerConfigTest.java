// code by jph
package ch.ethz.idsc.retina.dev.steer;

import ch.ethz.idsc.tensor.RealScalar;
import ch.ethz.idsc.tensor.Scalar;
import ch.ethz.idsc.tensor.qty.Quantity;
import ch.ethz.idsc.tensor.qty.QuantityMagnitude;
import ch.ethz.idsc.tensor.qty.Unit;
import junit.framework.TestCase;

public class SteerConfigTest extends TestCase {
  public void testSimple() {
    Scalar q = Quantity.of(2, "km*NOU");
    Scalar r = QuantityMagnitude.SI().in(Unit.of("m*NOU")).apply(q);
    assertEquals(r, RealScalar.of(2000));
  }
}
