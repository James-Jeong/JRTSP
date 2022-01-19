package rtsp.module.netty.handler;

import com.fsm.module.StateHandler;
import io.lindstrom.m3u8.model.MediaPlaylist;
import io.lindstrom.m3u8.model.MediaSegment;
import io.lindstrom.m3u8.parser.MediaPlaylistParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.apache.commons.net.ntp.TimeStamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rtsp.config.ConfigManager;
import rtsp.ffmpeg.FfmpegManager;
import rtsp.fsm.RtspEvent;
import rtsp.module.Streamer;
import rtsp.module.VideoStream;
import rtsp.module.base.RtspUnit;
import rtsp.module.sdp.base.Sdp;
import rtsp.protocol.RtpPacket;
import rtsp.service.AppInstance;
import rtsp.service.scheduler.job.Job;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class RtpSender extends Job {
    
    private static final Logger logger = LoggerFactory.getLogger(RtpSender.class);

    ///////////////////////////////////////////////////////////////////////////
    public static final int TS_PACKET_SIZE = 188;

    private final RtpPacket rtpPacket = new RtpPacket();

    private final FfmpegManager ffmpegManager;
    private final VideoStream video;
    private final double fileTime;
    private final double npt1;
    private final double npt2;
    private final StateHandler rtspStateHandler;
    private final RtspUnit rtspUnit;
    private final Streamer streamer;
    private final int destPort;
    ///////////////////////////////////////////////////////////////////////////

    ///////////////////////////////////////////////////////////////////////////

    /**
     * @param name Job 이름
     * @param initialDelay Job 최초 실행 전 딜레이
     * @param interval Job 실행 간격 시간
     * @param timeUnit Job 실행 간격 시간 단위
     * @param priority Job 우선순위
     * @param totalRunCount Job 전체 실행 횟수
     * @param isLasted Job 영구 진행 여부
     * @param ffmpegManager FfmpegManager
     * @param video VideoStream
     * @param fileTime HLS interval time
     * @param npt1 Start time
     * @param npt2 End time
     * @param rtspStateHandler StateHandler
     * @param rtspUnit RtspUnit
     * @param streamer Streamer
     * @param destPort Destination RTP Port
     */
    public RtpSender(String name,
                     int initialDelay, int interval, TimeUnit timeUnit,
                     int priority, int totalRunCount, boolean isLasted,
                     FfmpegManager ffmpegManager, VideoStream video,
                     double fileTime, double npt1, double npt2,
                     StateHandler rtspStateHandler, RtspUnit rtspUnit, Streamer streamer, int destPort) {
        super(name, initialDelay, interval, timeUnit, priority, totalRunCount, isLasted);

        this.ffmpegManager = ffmpegManager;
        this.video = video;
        this.fileTime = fileTime;
        this.npt1 = npt1;
        this.npt2 = npt2;
        this.rtspStateHandler = rtspStateHandler;
        this.rtspUnit = rtspUnit;
        this.streamer = streamer;
        this.destPort = destPort;
    }
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void run() {
        sendData();
    }

    /**
     * @fn private void sendData()
     * @brief 미리 생성된 M3U8 파일에 명시된 TS 파일을 로컬에서 읽어서 지정한 Destination 으로 RTP 패킷으로 패킹하여 보내는 함수
     */
    private void sendData() {
        try {
            ///////////////////////////////////////////////////////////////////////////
            // DIRECT PARSING IF ENABLED
            ConfigManager configManager = AppInstance.getInstance().getConfigManager();
            if (configManager.isM3u8DirectConverting()) {
                ffmpegManager.convertMp4ToM3u8(
                        video.getMp4FileName(),
                        video.getResultM3U8FilePath(),
                        (long) fileTime,
                        (long) npt1,
                        (long) npt2
                );
            }
            ///////////////////////////////////////////////////////////////////////////

            ///////////////////////////////////////////////////////////////////////////
            // CHECK M3U8 FILE
            File m3u8File = new File(video.getResultM3U8FilePath());
            if (!m3u8File.exists() || !m3u8File.isFile()) {
                logger.warn("({}) ({}) ({}) M3U8 File is wrong.Fail to get the m3u8 data. (m3u8FilePath={})", getName(), rtspUnit.getRtspUnitId(), streamer.getSessionId(), video.getResultM3U8FilePath());
                return;
            }

            byte[] m3u8ByteData = Files.readAllBytes(
                    Paths.get(
                            video.getResultM3U8FilePath()
                    )
            );

            if (m3u8ByteData.length == 0) {
                logger.warn("({}) ({}) ({}) Fail to process the PLAY request. Fail to get the m3u8 data. (rtspUnit={}, destPort={})", getName(), rtspUnit.getRtspUnitId(), streamer.getSessionId(), rtspUnit, destPort);
                rtspStateHandler.fire(
                        RtspEvent.PLAY_FAIL,
                        rtspUnit.getStateManager().getStateUnit(rtspUnit.getRtspStateUnitId())
                );
                return;
            }
            ///////////////////////////////////////////////////////////////////////////

            ///////////////////////////////////////////////////////////////////////////
            // GET MEDIA SEGMENT LIST
            List<MediaSegment> mediaSegmentList;
            MediaPlaylistParser parser = new MediaPlaylistParser();
            MediaPlaylist playlist = parser.readPlaylist(Paths.get(video.getResultM3U8FilePath()));
            if (playlist != null) {
                String m3u8PathOnly = video.getResultM3U8FilePath();
                m3u8PathOnly = m3u8PathOnly.substring(
                        0,
                        m3u8PathOnly.lastIndexOf("/")
                );
                streamer.setM3u8PathOnly(m3u8PathOnly);
                mediaSegmentList = playlist.mediaSegments();

                logger.debug("({}) ({}) ({}) mediaSegmentList: {}", getName(), rtspUnit.getRtspUnitId(), streamer.getSessionId(), mediaSegmentList);
                streamer.setMediaSegmentList(mediaSegmentList);
            } else {
                logger.warn("({}) ({}) ({}) Fail to stream the media. (rtpDestPort={})", getName(), rtspUnit.getRtspUnitId(), streamer.getSessionId(), destPort);
                rtspStateHandler.fire(
                        RtspEvent.PLAY_FAIL,
                        rtspUnit.getStateManager().getStateUnit(rtspUnit.getRtspStateUnitId())
                );
                return;
            }

            if (mediaSegmentList == null || mediaSegmentList.isEmpty()) {
                logger.warn("({}) ({}) ({}) Media segment list is empty.", getName(), rtspUnit.getRtspUnitId(), streamer.getSessionId());
                rtspStateHandler.fire(
                        RtspEvent.PLAY_FAIL,
                        rtspUnit.getStateManager().getStateUnit(rtspUnit.getRtspStateUnitId())
                );
                return;
            }
            ///////////////////////////////////////////////////////////////////////////

            ///////////////////////////////////////////////////////////////////////////
            // SEND M3U8
            ByteBuf buf = Unpooled.copiedBuffer(m3u8ByteData);
            streamer.send(
                    buf,
                    streamer.getDestIp(),
                    streamer.getDestPort()
            );

            logger.debug("({}) ({}) ({}) << Send M3U8 (destIp={}, destPort={})\n{}(size={})",
                    getName(), rtspUnit.getRtspUnitId(),
                    streamer.getSessionId(), streamer.getDestIp(), streamer.getDestPort(),
                    new String(m3u8ByteData, StandardCharsets.UTF_8), m3u8ByteData.length
            );
            ///////////////////////////////////////////////////////////////////////////

            ///////////////////////////////////////////////////////////////////////////
            // SEND TS FILES
            // TS Packet Total byte : 188 (4(header) + 184 (PES, Packetized Elementary Streams))
            mediaSegmentList = streamer.getMediaSegmentList();
            String m3u8PathOnly = streamer.getM3u8PathOnly();

            int totalByteSize = 0;
            byte[] buffer = new byte[TS_PACKET_SIZE];

            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            InputStream inputStream = null;

            // 1초에 90000 바이트를 보내야 함 > 2ms에 180 바이트를 보내야함
            // 1 KB per 20 time units > 90 KB per 1800 time units
            int delay = 5; // (ms) TODO
            int read;
            TimeUnit timeUnit = TimeUnit.MILLISECONDS;

            try {
                for (MediaSegment mediaSegment : mediaSegmentList) {
                    int curByteSize = 0;

                    String tsFileName = mediaSegment.uri();
                    tsFileName = m3u8PathOnly + File.separator + tsFileName;
                    inputStream = new FileInputStream(tsFileName);

                    while ((read = inputStream.read(buffer)) != -1) {
                        if (streamer.isPaused()) { break; }

                        byteArrayOutputStream.reset();
                        byteArrayOutputStream.write(buffer, 0, read);
                        byte[] curData = byteArrayOutputStream.toByteArray();
                        sendRtpPacket(streamer, curData);
                        curByteSize += curData.length;

                        timeUnit.sleep((long) delay * (rtspUnit.getCongestionLevel() + 1));
                    }

                    totalByteSize += curByteSize;
                    logger.debug("({}) ({}) ({}) [SEND TS BYTES: {}] (bitrate={}, {})", getName(), rtspUnit.getRtspUnitId(), streamer.getSessionId(), curByteSize, mediaSegment.bitrate(), mediaSegment);

                    inputStream.close();
                    inputStream = null;

                    if (streamer.isPaused()) { break; }
                }

                logger.debug("({}) ({}) ({}) [SEND TOTAL BYTES: {}]", getName(), rtspUnit.getRtspUnitId(), streamer.getSessionId(), totalByteSize);
            } finally {
                try {
                    byteArrayOutputStream.close();
                } catch (IOException e) {
                    // ignore
                }

                try {
                    if (inputStream != null) {
                        inputStream.close();
                    }
                } catch (IOException e) {
                    // ignore
                }
            }
            ///////////////////////////////////////////////////////////////////////////
        } catch (Exception e) {
            logger.warn("RtspChannelHandler.sendData.Exception", e);
        }
    }

    // TODO
    private void sendRtpPacket(Streamer streamer, byte[] data) {
        int curSeqNum = streamer.getCurSeqNum();
        long curTimeStamp = streamer.getCurTimeStamp();
        /*if (curTimeStamp <= 0) {
            curTimeStamp = ((int) TimeStamp.getCurrentTime().getTime() / 1000);
        }*/

        rtpPacket.setValue(
                2, 0, 0, 0, 0, ConfigManager.MP2T_TYPE,
                curSeqNum, curTimeStamp, streamer.getSsrc(), data, data.length
        );

        byte[] totalRtpData = rtpPacket.getData();
        ByteBuf buf = Unpooled.copiedBuffer(totalRtpData);
        streamer.send(
                buf,
                streamer.getDestIp(),
                streamer.getDestPort()
        );

        streamer.setCurSeqNum(curSeqNum + 1); // TODO
        streamer.setCurTimeStamp(curTimeStamp + 1000);

        /*logger.debug("({}) ({}) ({}) << Send TS RTP [{}] (destIp={}, destPort={}, totalSize={}, payloadSize={})",
                name, rtspUnitId, streamer.getSessionId(), rtpPacket, streamer.getDestIp(), streamer.getDestPort(), totalRtpData.length, data.length
        );*/
    }

}
