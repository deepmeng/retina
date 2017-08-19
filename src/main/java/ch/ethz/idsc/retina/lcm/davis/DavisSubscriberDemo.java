// code by jph
package ch.ethz.idsc.retina.lcm.davis;

import java.io.IOException;
import java.nio.ByteBuffer;

import ch.ethz.idsc.retina.dev.davis.DavisDevice;
import ch.ethz.idsc.retina.dev.davis._240c.Davis240c;
import ch.ethz.idsc.retina.dev.davis.app.AccumulatedEventsImage;
import ch.ethz.idsc.retina.dev.davis.app.DavisDefaultDisplay;
import ch.ethz.idsc.retina.dev.davis.data.DavisApsDatagramDecoder;
import ch.ethz.idsc.retina.dev.davis.data.DavisDvsDatagramDecoder;
import ch.ethz.idsc.retina.dev.davis.data.DavisImuLcmDecoder;
import idsc.BinaryBlob;
import idsc.DavisImu;
import lcm.lcm.LCM;
import lcm.lcm.LCMDataInputStream;
import lcm.lcm.LCMSubscriber;

/** opens a frame to visualize sensor data from the Davis240c camera
 * which is received via three lcm channels
 * <ul>
 * <li>aps grayscale images
 * <li>dvs events
 * <li>imu
 * </ul> */
class DavisSubscriberDemo {
  private final LCM lcm = LCM.getSingleton();
  private final DavisDvsDatagramDecoder davisDvsDatagramDecoder = new DavisDvsDatagramDecoder();
  private final DavisApsDatagramDecoder davisApsDatagramDecoder = new DavisApsDatagramDecoder();
  private final DavisImuLcmDecoder davisImuLcmDecoder = new DavisImuLcmDecoder();
  private final String cameraId;

  public DavisSubscriberDemo(String cameraId) {
    this.cameraId = cameraId;
  }

  public void subscribe() {
    lcm.subscribe(DavisDvsBlockPublisher.channel(cameraId), new LCMSubscriber() {
      @Override
      public void messageReceived(LCM lcm, String channel, LCMDataInputStream ins) {
        try {
          BinaryBlob dvsBlockLcm = new BinaryBlob(ins);
          davisDvsDatagramDecoder.decode(ByteBuffer.wrap(dvsBlockLcm.data));
        } catch (IOException exception) {
          exception.printStackTrace();
        }
      }
    });
    lcm.subscribe(DavisApsBlockPublisher.channel(cameraId), new LCMSubscriber() {
      @Override
      public void messageReceived(LCM lcm, String channel, LCMDataInputStream ins) {
        try {
          BinaryBlob apsBlockLcm = new BinaryBlob(ins);
          davisApsDatagramDecoder.decode(ByteBuffer.wrap(apsBlockLcm.data));
        } catch (IOException exception) {
          exception.printStackTrace();
        }
      }
    });
    lcm.subscribe(DavisImuFramePublisher.channel(cameraId), new LCMSubscriber() {
      @Override
      public void messageReceived(LCM lcm, String channel, LCMDataInputStream ins) {
        try {
          DavisImu davisImu = new DavisImu(ins);
          davisImuLcmDecoder.decode(davisImu);
        } catch (IOException exception) {
          exception.printStackTrace();
        }
      }
    });
  }

  public static void main(String[] args) throws Exception {
    DavisDevice davisDevice = Davis240c.INSTANCE;
    DavisSubscriberDemo davisSubscriberDemo = new DavisSubscriberDemo(DavisSerial.FX2_02460045.name());
    DavisDefaultDisplay davisImageDisplay = new DavisDefaultDisplay(davisDevice);
    // handle dvs
    AccumulatedEventsImage accumulatedEventsImage = new AccumulatedEventsImage(davisDevice, 10000);
    davisSubscriberDemo.davisDvsDatagramDecoder.addListener(accumulatedEventsImage);
    accumulatedEventsImage.addListener(davisImageDisplay);
    // handle aps
    davisSubscriberDemo.davisApsDatagramDecoder.addListener(davisImageDisplay);
    // handle imu
    davisSubscriberDemo.davisImuLcmDecoder.addListener(davisImageDisplay);
    // start to listen
    davisSubscriberDemo.subscribe();
  }
}
