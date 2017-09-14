// code by jph
package ch.ethz.idsc.retina.gui.gokart;

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
import javax.swing.JTextField;
import javax.swing.JToolBar;

import ch.ethz.idsc.retina.dev.rimo.RimoGetEvent;
import ch.ethz.idsc.retina.dev.rimo.RimoPutEvent;
import ch.ethz.idsc.retina.dev.rimo.RimoSocket;
import ch.ethz.idsc.retina.util.HexStrings;
import ch.ethz.idsc.retina.util.data.Word;
import ch.ethz.idsc.retina.util.gui.SpinnerLabel;
import ch.ethz.idsc.retina.util.io.ByteArrayConsumer;
import ch.ethz.idsc.retina.util.io.DatagramSocketManager;

public class RimoComponent extends InterfaceComponent implements ByteArrayConsumer {
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
  private final JTextField jTextFieldLRecv;
  private final JTextField jTextFieldRRecv;

  public RimoComponent() {
    // LEFT
    {
      JToolBar jToolBar = createRow("LEFT command");
      spinnerLabelLCmd.setList(COMMANDS);
      spinnerLabelLCmd.setValueSafe(COMMANDS.get(0));
      spinnerLabelLCmd.addToComponent(jToolBar, new Dimension(200, 20), "");
    }
    { // command speed
      JToolBar jToolBar = createRow("LEFT speed");
      sliderExtLVel = SliderExt.wrap(new JSlider(-8000, 8000, 0));
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
      sliderExtRVel = SliderExt.wrap(new JSlider(-8000, 8000, 0));
      sliderExtRVel.addToComponent(jToolBar);
    }
    { // reception
      jTextFieldLRecv = createReading("LEFT recv");
      datagramSocketManager.addListener(this);
    }
    { // reception
      jTextFieldRRecv = createReading("RIGHT recv");
      datagramSocketManager.addListener(this);
    }
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
          }
          {
            RimoPutEvent rimoPutEvent = new RimoPutEvent();
            rimoPutEvent.command = spinnerLabelRCmd.getValue().getShort();
            rimoPutEvent.speed = (short) sliderExtRVel.jSlider.getValue();
            rimoPutEvent.insert(byteBuffer);
          }
          System.out.println("rimo put=" + HexStrings.from(data));
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
      jTextFieldLRecv.setText(rimoGetL.toInfoString());
      jTextFieldRRecv.setText(rimoGetR.toInfoString());
    } catch (Exception e) {
      System.out.println("fail decode RimoGet, received=" + length);
      // TODO: handle exception
    }
  }

  @Override
  public String connectionInfoRemote() {
    return String.format("%s:%d", RimoSocket.REMOTE_ADDRESS, RimoSocket.REMOTE_PORT);
  }

  @Override
  public String connectionInfoLocal() {
    return String.format("%s:%d", RimoSocket.LOCAL_ADDRESS, RimoSocket.LOCAL_PORT);
  }
}