package rtsp.module.mpegts.content;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rtsp.module.mpegts.content.sinks.MTSSink;
import rtsp.module.mpegts.content.sources.MTSSource;

import java.nio.ByteBuffer;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;

public class MpegTsStreamer {

    static final Logger logger = LoggerFactory.getLogger("streamer");

    private final MTSSource source;
    private final MTSSink sink;

    private ArrayBlockingQueue<MpegTsPacket> buffer;
    private final int bufferSize;
    private boolean endOfSourceReached;
    private boolean streamingShouldStop;

    private PATSection patSection;
    private TreeMap<Integer, PMTSection> pmtSection;

    private Thread bufferingThread;
    private Thread streamingThread;

    private MpegTsStreamer(MTSSource source, MTSSink sink, int bufferSize) {
        this.source = source;
        this.sink = sink;
        this.bufferSize = bufferSize;
    }

    public static StreamerBuilder builder() {
        return new StreamerBuilder();
    }

    public void stream() {
        buffer = new ArrayBlockingQueue<>(bufferSize);
        patSection = null;
        pmtSection = Maps.newTreeMap();
        endOfSourceReached = false;
        streamingShouldStop = false;
        logger.info("PreBuffering {} packets", bufferSize);
        try {
            preBuffer();
        } catch (Exception e) {
            throw new IllegalStateException("Error while bufering", e);
        }
        logger.info("Done PreBuffering");

        bufferingThread = new Thread(this::fillBuffer, "buffering");
        bufferingThread.start();

        streamingThread = new Thread(this::internalStream, "streaming");
        streamingThread.start();
    }

    public void stop() {
        streamingShouldStop = true;
        buffer.clear();
        try {
            bufferingThread.join();
            streamingThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        buffer = null;
        bufferingThread = streamingThread = null;
    }

    private void internalStream() {
        boolean resetState = false;
        MpegTsPacket packet;
        long packetCount = 0;
        long pcrCount = 0;
        //long pcrPidPacketCount = 0;
        Long firstPcrValue = null;
        Long firstPcrTime = null;
        //Long firstPcrPacketCount = null;
        Long lastPcrValue = null;
        Long lastPcrTime = null;
        //Long lastPcrPacketCount = null;
        Long averageSleep = null;
        while (!streamingShouldStop) {
            if (resetState) {
                pcrCount = 0;
                firstPcrValue = null;
                firstPcrTime = null;
                lastPcrValue = null;
                lastPcrTime = null;
                averageSleep = null;
                resetState = false;
            }

            // Initialize time to sleep
            long sleepNanos = 0;

            packet = buffer.poll();

            if (packet == null) {
                if (endOfSourceReached) {
                    packet = buffer.poll();
                    if (packet == null) {
                        break;
                    }
                } else {
                    continue;
                }
            }

            int pid = packet.getPid();

            if (pid == 0 && packet.isPayloadUnitStartIndicator()) {
                ByteBuffer payload = packet.getPayload();
                payload.rewind();
                int pointer = payload.get() & 0xff;
                payload.position(payload.position() + pointer);
                patSection = PATSection.parse(payload);
                if (patSection != null) {
                    for (Integer pmtPid : pmtSection.keySet()) {
                        if (!patSection.getPrograms().containsValue(pmtPid)) {
                            pmtSection.remove(pmtPid);
                        }
                    }
                }
            }

            if (pid != 0 && patSection != null) {
                if (patSection.getPrograms().containsValue(pid)) {
                    if (packet.isPayloadUnitStartIndicator()) {
                        ByteBuffer payload = packet.getPayload();
                        payload.rewind();
                        int pointer = payload.get() & 0xff;
                        payload.position(payload.position() + pointer);
                        pmtSection.put(pid, PMTSection.parse(payload));
                    }
                }

            }

            // Check PID matches PCR PID
            if (true) {//mtsPacket.pid == pmt.getPcrPid()) {
                //pcrPidPacketCount++;

                if (averageSleep != null) {
                    sleepNanos = averageSleep;
                } else {
//						if (pcrPidPacketCount < 2) {
//							if (pcrPidPacketCount % 10 == 0) {
//								sleepNanos = 15;
//							}
//						}
                }
            }

            // Check for PCR
            if (packet.getAdaptationField() != null) {
                if (packet.getAdaptationField().getPcr() != null) {
                    if (packet.getPid() == getPCRPid()) {
                        if (!packet.getAdaptationField().isDiscontinuityIndicator()) {
                            // Get PCR and current nano time
                            long pcrValue = packet.getAdaptationField().getPcr().getValue();
                            long pcrTime = System.nanoTime();
                            pcrCount++;

                            // Compute sleepNanosOrig
                            Long sleepNanosOrig = null;
                            if (firstPcrValue == null || firstPcrTime == null) {
                                firstPcrValue = pcrValue;
                                firstPcrTime = pcrTime;
                                //firstPcrPacketCount = pcrPidPacketCount;
                            } else if (pcrValue > firstPcrValue) {
                                sleepNanosOrig = ((pcrValue - firstPcrValue) / 27 * 1000) - (pcrTime - firstPcrTime);
                            }

                            // Compute sleepNanosPrevious
                            Long sleepNanosPrevious = null;
                            if (lastPcrValue != null && lastPcrTime != null) {
                                if (pcrValue <= lastPcrValue) {
                                    System.err.println("PCR discontinuity ! " + packet.getPid());
                                    resetState = true;
                                } else {
                                    sleepNanosPrevious = ((pcrValue - lastPcrValue) / 27 * 1000) - (pcrTime - lastPcrTime);
                                }
                            }
//								System.out.println("pcrValue=" + pcrValue + ", lastPcrValue=" + lastPcrValue + ", sleepNanosPrevious=" + sleepNanosPrevious + ", sleepNanosOrig=" + sleepNanosOrig);

                            // Set sleep time based on PCR if possible
                            if (sleepNanosPrevious != null) {
                                // Safety : We should never have to wait more than 100ms
                                if (sleepNanosPrevious > 100000000) {
                                    logger.warn("PCR sleep ignored, too high !");
                                    resetState = true;
                                } else {
                                    sleepNanos = sleepNanosPrevious;
//										averageSleep = sleepNanosPrevious / (pcrPidPacketCount - lastPcrPacketCount - 1);
                                }
                            }

                            // Set lastPcrValue/lastPcrTime
                            lastPcrValue = pcrValue;
                            lastPcrTime = pcrTime + sleepNanos;
                            //lastPcrPacketCount = pcrPidPacketCount;
                        } else {
                            logger.warn("Skipped PCR - Discontinuity indicator");
                        }
                    } else {
                        logger.debug("Skipped PCR - PID does not match");
                    }
                }
            }

            // Sleep if needed
            if (sleepNanos > 0) {
                logger.trace("Sleeping " + sleepNanos / 1000000 + " millis, " + sleepNanos % 1000000 + " nanos");
                try {
                    Thread.sleep(sleepNanos / 1000000, (int) (sleepNanos % 1000000));
                } catch (InterruptedException e) {
                    logger.warn("Streaming sleep interrupted!");
                }
            }

            // Stream packet
            // System.out.println("Streaming packet #" + packetCount + ", PID=" + mtsPacket.getPid() + ", pcrCount=" + pcrCount + ", continuityCounter=" + mtsPacket.getContinuityCounter());

            try {
                sink.send(packet);
            } catch (Exception e) {
                logger.error("Error sending packet to sink", e);
            }

            packetCount++;
        }
        logger.info("Sent {} MPEG-TS packets", packetCount);
    }

    private void preBuffer() throws Exception {
        MpegTsPacket packet;
        int packetNumber = 0;
        while ((packetNumber < bufferSize) && (packet = source.nextPacket()) != null) {
            buffer.add(packet);
            packetNumber++;
        }
    }

    private void fillBuffer() {
        try {
            MpegTsPacket packet;
            while (!streamingShouldStop && (packet = source.nextPacket()) != null) {
                boolean put = false;
                while (!put) {
                    try {
                        buffer.put(packet);
                        put = true;
                    } catch (InterruptedException ignored) {

                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error reading from source", e);
        } finally {
            endOfSourceReached = true;
        }
    }

    private int getPCRPid() {
        if ((!pmtSection.isEmpty())) {
            // TODO change this
            return pmtSection.values().iterator().next().getPcrPid();
        }
        return -1;
    }

    public static class StreamerBuilder {
        private MTSSink sink;
        private MTSSource source;
        private int bufferSize = 1000;

        public StreamerBuilder setSink(MTSSink sink) {
            this.sink = sink;
            return this;
        }

        public StreamerBuilder setSource(MTSSource source) {
            this.source = source;
            return this;
        }

        public StreamerBuilder setBufferSize(int bufferSize) {
            this.bufferSize = bufferSize;
            return this;
        }

        public MpegTsStreamer build() {
            Preconditions.checkNotNull(sink);
            Preconditions.checkNotNull(source);
            return new MpegTsStreamer(source, sink, bufferSize);
        }
    }
}