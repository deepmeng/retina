// code by jph
package ch.ethz.idsc.gokart.offline.tab;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

import ch.ethz.idsc.gokart.gui.GokartLcmChannel;
import ch.ethz.idsc.gokart.gui.GokartStatusEvent;
import ch.ethz.idsc.gokart.gui.top.ChassisGeometry;
import ch.ethz.idsc.gokart.lcm.autobox.RimoLcmServer;
import ch.ethz.idsc.gokart.offline.api.OfflineTableSupplier;
import ch.ethz.idsc.retina.dev.joystick.GokartJoystickInterface;
import ch.ethz.idsc.retina.dev.joystick.JoystickDecoder;
import ch.ethz.idsc.retina.dev.joystick.JoystickEvent;
import ch.ethz.idsc.retina.dev.rimo.RimoGetEvent;
import ch.ethz.idsc.retina.dev.rimo.RimoGetTire;
import ch.ethz.idsc.retina.dev.rimo.RimoPutEvent;
import ch.ethz.idsc.retina.dev.rimo.RimoPutHelper;
import ch.ethz.idsc.retina.dev.rimo.RimoPutTire;
import ch.ethz.idsc.retina.dev.steer.SteerPutEvent;
import ch.ethz.idsc.retina.lcm.VectorFloatBlob;
import ch.ethz.idsc.retina.util.math.Magnitude;
import ch.ethz.idsc.retina.util.math.SI;
import ch.ethz.idsc.tensor.RealScalar;
import ch.ethz.idsc.tensor.Scalar;
import ch.ethz.idsc.tensor.Scalars;
import ch.ethz.idsc.tensor.Tensor;
import ch.ethz.idsc.tensor.io.TableBuilder;
import ch.ethz.idsc.tensor.qty.Quantity;
import ch.ethz.idsc.tensor.sca.Ramp;

public class RimoRateJoystickTable implements OfflineTableSupplier {
  private final TableBuilder tableBuilder = new TableBuilder();
  private final Scalar delta;
  private final ByteOrder byteOrder;
  // ---
  private Scalar time_next = Quantity.of(0, SI.SECOND);
  private RimoGetEvent rge;
  private RimoPutEvent rpe;
  private GokartStatusEvent gse;
  private GokartJoystickInterface gji;

  /** @param delta
   * @param byteOrder use BIG_ENDIAN for log files on day: 20180427 */
  public RimoRateJoystickTable(Scalar delta, ByteOrder byteOrder) {
    this.delta = delta;
    this.byteOrder = byteOrder;
  }

  @Override // from OfflineLogListener
  public void event(Scalar time, String channel, ByteBuffer byteBuffer) {
    if (channel.equals(RimoLcmServer.CHANNEL_GET)) {
      rge = new RimoGetEvent(byteBuffer);
    } else //
    if (channel.equals(RimoLcmServer.CHANNEL_PUT)) {
      rpe = RimoPutHelper.from(byteBuffer);
    } else //
    if (channel.equals(GokartLcmChannel.STATUS)) {
      gse = new GokartStatusEvent(byteBuffer);
    } else //
    if (channel.equals("joystick.generic_xbox_pad")) {
      JoystickEvent joystickEvent = JoystickDecoder.decode(byteBuffer);
      gji = (GokartJoystickInterface) joystickEvent;
    } else //
    if (channel.equals(GokartLcmChannel.RIMO_CONTROLLER_PI)) {
      byteBuffer.order(byteOrder);
      VectorFloatBlob.decode(byteBuffer); // TODO not used yet
    }
    if (Scalars.lessThan(time_next, time)) {
      if (Objects.nonNull(rge) && //
          Objects.nonNull(rpe) && //
          Objects.nonNull(gse) && //
          gse.isSteerColumnCalibrated() && //
          Objects.nonNull(gji)) {
        // System.out.println("export " + time.number().doubleValue());
        time_next = time.add(delta);
        // ---
        Tensor rates = rge.getAngularRate_Y_pair();
        Scalar speed = ChassisGeometry.GLOBAL.odometryTangentSpeed(rge);
        Scalar rate = ChassisGeometry.GLOBAL.odometryTurningRate(rge);
        Scalar factor = gji.isAutonomousPressed() ? RealScalar.ONE : RealScalar.ZERO;
        tableBuilder.appendRow( //
            time.map(Magnitude.SECOND), //
            rpe.getTorque_Y_pair().map(RimoPutTire.MAGNITUDE_ARMS), // ARMS
            rates.map(RimoGetTire.MAGNITUDE_RATE), // rad/s
            speed.map(Magnitude.VELOCITY), // m/s
            rate.map(Magnitude.ANGULAR_RATE), //
            gse.getSteerColumnEncoderCentered().map(SteerPutEvent.ENCODER), //
            gji.getAheadAverage(), //
            Ramp.FUNCTION.apply(gji.getAheadAverage()).multiply(factor) //
        );
      }
    }
  }

  @Override // from OfflineTableSupplier
  public Tensor getTable() {
    return tableBuilder.toTable();
  }
}
