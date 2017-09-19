// code by jph
package ch.ethz.idsc.retina.gui.gokart;

import java.awt.Color;
import java.awt.Dimension;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.TimerTask;

import javax.swing.JSlider;
import javax.swing.JToolBar;

import ch.ethz.idsc.retina.dev.joystick.GenericXboxPadJoystick;
import ch.ethz.idsc.retina.dev.joystick.JoystickEvent;
import ch.ethz.idsc.retina.dev.rimo.RimoGetEvent;
import ch.ethz.idsc.retina.dev.rimo.RimoGetListener;
import ch.ethz.idsc.retina.dev.rimo.RimoPutEvent;
import ch.ethz.idsc.retina.dev.rimo.RimoSocket;
import ch.ethz.idsc.retina.dev.steer.SteerGetEvent;
import ch.ethz.idsc.retina.dev.steer.SteerGetListener;
import ch.ethz.idsc.retina.util.data.Word;
import ch.ethz.idsc.retina.util.gui.SpinnerLabel;
import ch.ethz.idsc.retina.util.io.ByteArrayConsumer;
import ch.ethz.idsc.retina.util.io.DatagramSocketManager;
import ch.ethz.idsc.tensor.RealScalar;
import ch.ethz.idsc.tensor.Scalar;
import ch.ethz.idsc.tensor.Tensor;
import ch.ethz.idsc.tensor.img.ColorDataGradients;
import ch.ethz.idsc.tensor.img.ColorFormat;
import ch.ethz.idsc.tensor.qty.Quantity;
import ch.ethz.idsc.tensor.sca.Clip;

public class RimoComponent extends InterfaceComponent implements ByteArrayConsumer, RimoGetListener, SteerGetListener {
  public static final List<Word> COMMANDS = Arrays.asList( //
      Word.createShort("OPERATION", (short) 0x0009) //
  );
  // ---
  private final DatagramSocketManager datagramSocketManager = //
      DatagramSocketManager.local(new byte[2 * RimoGetEvent.LENGTH], RimoSocket.LOCAL_PORT, RimoSocket.LOCAL_ADDRESS);
  private TimerTask timerTask = null;
  private final SpinnerLabel<Word> spinnerLabelLCmd = new SpinnerLabel<>();
  private final SliderExt sliderExtLVel;
  private final SpinnerLabel<Word> spinnerLabelRCmd = new SpinnerLabel<>();
  private final SliderExt sliderExtRVel;
  private final RimoGetFields rimoGetFieldsL = new RimoGetFields();
  private final RimoGetFields rimoGetFieldsR = new RimoGetFields();
  private RimoPutEvent rimoPutEventL = new RimoPutEvent();
  private RimoPutEvent rimoPutEventR = new RimoPutEvent();

  public RimoComponent() {
    datagramSocketManager.addListener(this);
    // LEFT
    {
      JToolBar jToolBar = createRow("LEFT command");
      spinnerLabelLCmd.setList(COMMANDS);
      spinnerLabelLCmd.setValueSafe(COMMANDS.get(0));
      spinnerLabelLCmd.addToComponent(jToolBar, new Dimension(200, 20), "");
    }
    { // command speed
      JToolBar jToolBar = createRow("LEFT speed");
      sliderExtLVel = SliderExt.wrap(new JSlider(-RimoPutEvent.MAX_SPEED, RimoPutEvent.MAX_SPEED, 0));
      sliderExtLVel.addToComponent(jToolBar);
    }
    // RIGHT
    {
      JToolBar jToolBar = createRow("RIGHT command");
      spinnerLabelRCmd.setList(COMMANDS);
      spinnerLabelRCmd.setValueSafe(COMMANDS.get(0));
      spinnerLabelRCmd.addToComponent(jToolBar, new Dimension(200, 20), "");
    }
    { // command speed
      JToolBar jToolBar = createRow("RIGHT speed");
      sliderExtRVel = SliderExt.wrap(new JSlider(-RimoPutEvent.MAX_SPEED, RimoPutEvent.MAX_SPEED, 0));
      sliderExtRVel.addToComponent(jToolBar);
    }
    addSeparator();
    { // reception
      assign(rimoGetFieldsL, "LEFT");
      assign(rimoGetFieldsR, "RIGHT");
    }
  }

  private void assign(RimoGetFields rimoGetFields, String side) {
    rimoGetFields.jTF_status_word = createReading(side + " status word");
    // TODO NRJ background according to difference from target and actual speed
    rimoGetFields.jTF_actual_speed = createReading(side + " actual speed");
    rimoGetFields.jTF_rms_motor_current = createReading(side + " rms current");
    rimoGetFields.jTF_dc_bus_voltage = createReading(side + " dc bus voltage");
    // TODO NRJ background according to error code
    rimoGetFields.jTF_error_code = createReading(side + " error code");
    // TODO NRJ background according to temperature
    rimoGetFields.jTF_temperature_motor = createReading(side + " temp. motor");
    rimoGetFields.jTF_temperature_heatsink = createReading(side + " temp. heatsink");
  }

  @Override
  public void connectAction(int period, boolean isSelected) {
    if (isSelected) {
      datagramSocketManager.start();
      timerTask = new TimerTask() {
        @Override
        public void run() {
          byte data[] = new byte[2 * RimoPutEvent.LENGTH];
          ByteBuffer byteBuffer = ByteBuffer.wrap(data);
          byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
          {
            RimoPutEvent rimoPutEvent = new RimoPutEvent();
            rimoPutEvent.command = spinnerLabelLCmd.getValue().getShort();
            rimoPutEvent.speed = (short) sliderExtLVel.jSlider.getValue();
            rimoPutEvent.insert(byteBuffer);
            rimoPutEventL = rimoPutEvent;
          }
          {
            RimoPutEvent rimoPutEvent = new RimoPutEvent();
            rimoPutEvent.command = spinnerLabelRCmd.getValue().getShort();
            rimoPutEvent.speed = (short) sliderExtRVel.jSlider.getValue();
            rimoPutEvent.insert(byteBuffer);
            rimoPutEventR = rimoPutEvent;
          }
          // System.out.println("rimo put=" + HexStrings.from(data));
          try {
            DatagramPacket datagramPacket = new DatagramPacket(data, data.length, //
                InetAddress.getByName(RimoSocket.REMOTE_ADDRESS), RimoSocket.REMOTE_PORT);
            datagramSocketManager.send(datagramPacket);
          } catch (Exception exception) {
            // ---
            System.out.println("RIMO SEND FAIL");
            exception.printStackTrace();
            System.exit(0); // TODO
          }
        }
      };
      timer.schedule(timerTask, 100, period);
    } else {
      if (Objects.nonNull(timerTask)) {
        timerTask.cancel();
        timerTask = null;
      }
      datagramSocketManager.stop();
    }
  }

  @Override
  public void accept(byte[] data, int length) {
    ByteBuffer byteBuffer = ByteBuffer.wrap(data);
    byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
    try {
      RimoGetEvent rimoGetL = new RimoGetEvent(byteBuffer);
      RimoGetEvent rimoGetR = new RimoGetEvent(byteBuffer);
      rimoGet(rimoGetL, rimoGetR);
    } catch (Exception exception) {
      System.out.println("fail decode RimoGet, received=" + length);
      // TODO: handle exception
    }
  }

  @Override
  public void rimoGet(RimoGetEvent rimoGetL, RimoGetEvent rimoGetR) {
    rimoGetFieldsL.updateText(rimoGetL);
    rimoGetFieldsR.updateText(rimoGetR);
    {
      double speedDiff = rimoPutEventL.speed - rimoGetL.actual_speed;
      Scalar scalar = RealScalar.of(speedDiff);
      scalar = Clip.function(-500, 500).apply(scalar);
      scalar = scalar.divide(RealScalar.of(1000)).add(RealScalar.of(0.5));
      Tensor vector = ColorDataGradients.THERMOMETER.apply(scalar);
      Color color = ColorFormat.toColor(vector);
      rimoGetFieldsL.jTF_actual_speed.setBackground(color);
    }
    {
      double speedDiff = rimoPutEventR.speed - rimoGetR.actual_speed;
      Scalar scalar = RealScalar.of(speedDiff);
      scalar = Clip.function(-500, 500).apply(scalar);
      scalar = scalar.divide(RealScalar.of(1000)).add(RealScalar.of(0.5));
      Tensor vector = ColorDataGradients.THERMOMETER.apply(scalar);
      Color color = ColorFormat.toColor(vector);
      rimoGetFieldsL.jTF_actual_speed.setBackground(color);
    }
    {
      rimoGetFieldsL.jTF_temperature_motor.setText(Quantity.of(rimoGetL.temperature_motor, "[C]").toString());
      double tempMotL = rimoGetL.temperature_motor;
      Scalar scalarL = RealScalar.of(tempMotL / 10);
      scalarL = Clip.unit().apply(scalarL);
      Tensor vectorL = ColorDataGradients.THERMOMETER.apply(scalarL);
      Color colorL = ColorFormat.toColor(vectorL);
      rimoGetFieldsL.jTF_temperature_motor.setBackground(colorL);
    }
    {
      rimoGetFieldsL.jTF_temperature_motor.setText(Quantity.of(rimoGetR.temperature_motor, "[C]").toString());
      double tempMotR = rimoGetR.temperature_motor;
      Scalar scalarR = RealScalar.of(tempMotR / 10);
      scalarR = Clip.unit().apply(scalarR);
      Tensor vectorR = ColorDataGradients.THERMOMETER.apply(scalarR);
      Color colorR = ColorFormat.toColor(vectorR);
      rimoGetFieldsL.jTF_temperature_motor.setBackground(colorR);
    }
  }

  @Override
  public void steerGet(SteerGetEvent steerGetEvent) {
    // TODO Auto-generated method stub
  }

  @Override
  public String connectionInfoRemote() {
    return String.format("%s:%d", RimoSocket.REMOTE_ADDRESS, RimoSocket.REMOTE_PORT);
  }

  @Override
  public String connectionInfoLocal() {
    return String.format("%s:%d", RimoSocket.LOCAL_ADDRESS, RimoSocket.LOCAL_PORT);
  }

  private int sign = 1;
  public int speedlimitjoystick = 1000;

  @Override
  public void joystick(JoystickEvent joystickEvent) {
    if (isJoystickEnabled()) {
      GenericXboxPadJoystick joystick = (GenericXboxPadJoystick) joystickEvent;
      if (joystick.isButtonPressedBack()) {
        sign = -1;
      }
      if (joystick.isButtonPressedStart()) {
        sign = 1;
      }
      double wheelL = joystick.getLeftSliderUnitValue();
      sliderExtLVel.jSlider.setValue((int) (wheelL * speedlimitjoystick * sign));
      double wheelR = joystick.getRightSliderUnitValue();
      sliderExtRVel.jSlider.setValue((int) (wheelR * speedlimitjoystick * sign));
    }
  }

  public void setspeedlimit(int i) {
    speedlimitjoystick = i;
  }
}
