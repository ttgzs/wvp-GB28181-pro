package com.genersoft.iot.vmp.media.zlm;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.genersoft.iot.vmp.conf.DynamicTask;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.media.event.MediaServerChangeEvent;
import com.genersoft.iot.vmp.media.event.MediaServerDeleteEvent;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.media.zlm.dto.MediaServer;
import com.genersoft.iot.vmp.media.zlm.dto.ZLMServerConfig;
import com.genersoft.iot.vmp.media.zlm.event.HookZlmServerKeepaliveEvent;
import com.genersoft.iot.vmp.media.zlm.event.HookZlmServerStartEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理zlm流媒体节点的状态
 */
@Component
public class ZLMMediaServerStatusManger {

    private final static Logger logger = LoggerFactory.getLogger(ZLMMediaServerStatusManger.class);

    private final Map<Object, MediaServer> offlineZlmPrimaryMap = new ConcurrentHashMap<>();
    private final Map<Object, MediaServer> offlineZlmsecondaryMap = new ConcurrentHashMap<>();
    private final Map<Object, Long> offlineZlmTimeMap = new ConcurrentHashMap<>();

    @Autowired
    private ZLMRESTfulUtils zlmresTfulUtils;

    @Autowired
    private IMediaServerService mediaServerService;

    @Autowired
    private DynamicTask dynamicTask;

    @Value("${server.ssl.enabled:false}")
    private boolean sslEnabled;

    @Value("${server.port}")
    private Integer serverPort;

    @Autowired
    private UserSetting userSetting;

    private final String type = "zlm";

    @Async("taskExecutor")
    @EventListener
    public void onApplicationEvent(MediaServerChangeEvent event) {
        if (event.getMediaServerItemList() == null
                || event.getMediaServerItemList().isEmpty()) {
            return;
        }
        for (MediaServer mediaServerItem : event.getMediaServerItemList()) {
            if (!type.equals(mediaServerItem.getType())) {
                continue;
            }
            logger.info("[ZLM-添加待上线节点] ID：" + mediaServerItem.getId());
            offlineZlmPrimaryMap.put(mediaServerItem.getId(), mediaServerItem);
            offlineZlmTimeMap.put(mediaServerItem.getId(), System.currentTimeMillis());
        }
    }

    @Async("taskExecutor")
    @EventListener
    public void onApplicationEvent(HookZlmServerStartEvent event) {
        if (event.getMediaServerItem() == null
                || !type.equals(event.getMediaServerItem().getType())
                || event.getMediaServerItem().isStatus()) {
            return;
        }
        MediaServer serverItem = mediaServerService.getOne(event.getMediaServerItem().getId());
        if (serverItem == null) {
            return;
        }
        logger.info("[ZLM-HOOK事件-服务启动] ID：" + event.getMediaServerItem().getId());
        online(serverItem, null);
    }

    @Async("taskExecutor")
    @EventListener
    public void onApplicationEvent(HookZlmServerKeepaliveEvent event) {
        if (event.getMediaServerItem() == null) {
            return;
        }
        MediaServer serverItem = mediaServerService.getOne(event.getMediaServerItem().getId());
        if (serverItem == null) {
            return;
        }
        logger.info("[ZLM-HOOK事件-心跳] ID：" + event.getMediaServerItem().getId());
        online(serverItem, null);
    }

    @Async("taskExecutor")
    @EventListener
    public void onApplicationEvent(MediaServerDeleteEvent event) {
        if (event.getMediaServerId() == null) {
            return;
        }
        logger.info("[ZLM-节点被移除] ID：" + event.getMediaServerId());
        offlineZlmPrimaryMap.remove(event.getMediaServerId());
        offlineZlmsecondaryMap.remove(event.getMediaServerId());
        offlineZlmTimeMap.remove(event.getMediaServerId());
    }

    @Scheduled(fixedDelay = 10*1000)   //每隔10秒检查一次
    public void execute(){
        // 初次加入的离线节点会在30分钟内，每间隔十秒尝试一次，30分钟后如果仍然没有上线，则每隔30分钟尝试一次连接
        if (offlineZlmPrimaryMap.isEmpty() && offlineZlmsecondaryMap.isEmpty()) {
            return;
        }
        if (!offlineZlmPrimaryMap.isEmpty()) {
            for (MediaServer mediaServerItem : offlineZlmPrimaryMap.values()) {
                if (offlineZlmTimeMap.get(mediaServerItem.getId()) <  System.currentTimeMillis() - 30*60*1000) {
                    offlineZlmsecondaryMap.put(mediaServerItem.getId(), mediaServerItem);
                    offlineZlmPrimaryMap.remove(mediaServerItem.getId());
                    continue;
                }
                logger.info("[ZLM-尝试连接] ID：{}, 地址： {}:{}", mediaServerItem.getId(), mediaServerItem.getIp(), mediaServerItem.getHttpPort());
                JSONObject responseJson = zlmresTfulUtils.getMediaServerConfig(mediaServerItem);
                ZLMServerConfig zlmServerConfig = null;
                if (responseJson == null) {
                    logger.info("[ZLM-尝试连接]失败, ID：{}, 地址： {}:{}", mediaServerItem.getId(), mediaServerItem.getIp(), mediaServerItem.getHttpPort());
                    continue;
                }
                JSONArray data = responseJson.getJSONArray("data");
                if (data == null || data.isEmpty()) {
                    logger.info("[ZLM-尝试连接]失败, ID：{}, 地址： {}:{}", mediaServerItem.getId(), mediaServerItem.getIp(), mediaServerItem.getHttpPort());
                }else {
                    zlmServerConfig = JSON.parseObject(JSON.toJSONString(data.get(0)), ZLMServerConfig.class);
                    initPort(mediaServerItem, zlmServerConfig);
                    online(mediaServerItem, zlmServerConfig);
                }
            }
        }
        if (!offlineZlmsecondaryMap.isEmpty()) {
            for (MediaServer mediaServerItem : offlineZlmsecondaryMap.values()) {
                if (offlineZlmTimeMap.get(mediaServerItem.getId()) <  System.currentTimeMillis() - 30*60*1000) {
                    continue;
                }
                logger.info("[ZLM-尝试连接] ID：{}, 地址： {}:{}", mediaServerItem.getId(), mediaServerItem.getIp(), mediaServerItem.getHttpPort());
                JSONObject responseJson = zlmresTfulUtils.getMediaServerConfig(mediaServerItem);
                ZLMServerConfig zlmServerConfig = null;
                if (responseJson == null) {
                    logger.info("[ZLM-尝试连接]失败, ID：{}, 地址： {}:{}", mediaServerItem.getId(), mediaServerItem.getIp(), mediaServerItem.getHttpPort());
                    offlineZlmTimeMap.put(mediaServerItem.getId(), System.currentTimeMillis());
                    continue;
                }
                JSONArray data = responseJson.getJSONArray("data");
                if (data == null || data.isEmpty()) {
                    logger.info("[ZLM-尝试连接]失败, ID：{}, 地址： {}:{}", mediaServerItem.getId(), mediaServerItem.getIp(), mediaServerItem.getHttpPort());
                    offlineZlmTimeMap.put(mediaServerItem.getId(), System.currentTimeMillis());
                }else {
                    zlmServerConfig = JSON.parseObject(JSON.toJSONString(data.get(0)), ZLMServerConfig.class);
                    initPort(mediaServerItem, zlmServerConfig);
                    online(mediaServerItem, zlmServerConfig);
                }
            }
        }
    }

    private void online(MediaServer mediaServerItem, ZLMServerConfig config) {
        offlineZlmPrimaryMap.remove(mediaServerItem.getId());
        offlineZlmsecondaryMap.remove(mediaServerItem.getId());
        offlineZlmTimeMap.remove(mediaServerItem.getId());
        if (!mediaServerItem.isStatus()) {
            logger.info("[ZLM-连接成功] ID：{}, 地址： {}:{}", mediaServerItem.getId(), mediaServerItem.getIp(), mediaServerItem.getHttpPort());
            mediaServerItem.setStatus(true);
            mediaServerItem.setHookAliveInterval(10F);
            mediaServerService.update(mediaServerItem);
            if(mediaServerItem.isAutoConfig()) {
                if (config == null) {
                    JSONObject responseJSON = zlmresTfulUtils.getMediaServerConfig(mediaServerItem);
                    JSONArray data = responseJSON.getJSONArray("data");
                    if (data != null && !data.isEmpty()) {
                        config = JSON.parseObject(JSON.toJSONString(data.get(0)), ZLMServerConfig.class);
                    }
                }
                if (config != null) {
                    setZLMConfig(mediaServerItem, "0".equals(config.getHookEnable())
                            || !Objects.equals(mediaServerItem.getHookAliveInterval(), config.getHookAliveInterval()));
                }
            }
        }
        // 设置两次心跳未收到则认为zlm离线
        String key = "zlm-keepalive-" + mediaServerItem.getId();
        dynamicTask.startDelay(key, ()->{
            logger.warn("[ZLM-心跳超时] ID：{}", mediaServerItem.getId());
            mediaServerItem.setStatus(false);
            offlineZlmPrimaryMap.put(mediaServerItem.getId(), mediaServerItem);
            offlineZlmTimeMap.put(mediaServerItem.getId(), System.currentTimeMillis());
            // TODO 发送离线通知
            mediaServerService.update(mediaServerItem);
        }, (int)(mediaServerItem.getHookAliveInterval() * 2 * 1000));
    }
    private void initPort(MediaServer mediaServerItem, ZLMServerConfig zlmServerConfig) {
        if (mediaServerItem.getHttpSSlPort() == 0) {
            mediaServerItem.setHttpSSlPort(zlmServerConfig.getHttpSSLport());
        }
        if (mediaServerItem.getRtmpPort() == 0) {
            mediaServerItem.setRtmpPort(zlmServerConfig.getRtmpPort());
        }
        if (mediaServerItem.getRtmpSSlPort() == 0) {
            mediaServerItem.setRtmpSSlPort(zlmServerConfig.getRtmpSslPort());
        }
        if (mediaServerItem.getRtspPort() == 0) {
            mediaServerItem.setRtspPort(zlmServerConfig.getRtspPort());
        }
        if (mediaServerItem.getRtspSSLPort() == 0) {
            mediaServerItem.setRtspSSLPort(zlmServerConfig.getRtspSSlport());
        }
        if (mediaServerItem.getRtpProxyPort() == 0) {
            mediaServerItem.setRtpProxyPort(zlmServerConfig.getRtpProxyPort());
        }
        mediaServerItem.setHookAliveInterval(10F);
    }

    public void setZLMConfig(MediaServer mediaServerItem, boolean restart) {
        logger.info("[媒体服务节点] 正在设置 ：{} -> {}:{}",
                mediaServerItem.getId(), mediaServerItem.getIp(), mediaServerItem.getHttpPort());
        String protocol = sslEnabled ? "https" : "http";
        String hookPrefix = String.format("%s://%s:%s/index/hook", protocol, mediaServerItem.getHookIp(), serverPort);

        Map<String, Object> param = new HashMap<>();
        param.put("api.secret",mediaServerItem.getSecret()); // -profile:v Baseline
        if (mediaServerItem.getRtspPort() != 0) {
            param.put("ffmpeg.snap", "%s -rtsp_transport tcp -i %s -y -f mjpeg -frames:v 1 %s");
        }
        param.put("hook.enable","1");
        param.put("hook.on_flow_report","");
        param.put("hook.on_play",String.format("%s/on_play", hookPrefix));
        param.put("hook.on_http_access","");
        param.put("hook.on_publish", String.format("%s/on_publish", hookPrefix));
        param.put("hook.on_record_ts","");
        param.put("hook.on_rtsp_auth","");
        param.put("hook.on_rtsp_realm","");
        param.put("hook.on_server_started",String.format("%s/on_server_started", hookPrefix));
        param.put("hook.on_shell_login","");
        param.put("hook.on_stream_changed",String.format("%s/on_stream_changed", hookPrefix));
        param.put("hook.on_stream_none_reader",String.format("%s/on_stream_none_reader", hookPrefix));
        param.put("hook.on_stream_not_found",String.format("%s/on_stream_not_found", hookPrefix));
        param.put("hook.on_server_keepalive",String.format("%s/on_server_keepalive", hookPrefix));
        param.put("hook.on_send_rtp_stopped",String.format("%s/on_send_rtp_stopped", hookPrefix));
        param.put("hook.on_rtp_server_timeout",String.format("%s/on_rtp_server_timeout", hookPrefix));
        param.put("hook.on_record_mp4",String.format("%s/on_record_mp4", hookPrefix));
        param.put("hook.timeoutSec","30");
        param.put("hook.alive_interval", mediaServerItem.getHookAliveInterval());
        // 推流断开后可以在超时时间内重新连接上继续推流，这样播放器会接着播放。
        // 置0关闭此特性(推流断开会导致立即断开播放器)
        // 此参数不应大于播放器超时时间
        // 优化此消息以更快的收到流注销事件
        param.put("protocol.continue_push_ms", "3000" );
        // 最多等待未初始化的Track时间，单位毫秒，超时之后会忽略未初始化的Track, 设置此选项优化那些音频错误的不规范流，
        // 等zlm支持给每个rtpServer设置关闭音频的时候可以不设置此选项
        if (mediaServerItem.isRtpEnable() && !ObjectUtils.isEmpty(mediaServerItem.getRtpPortRange())) {
            param.put("rtp_proxy.port_range", mediaServerItem.getRtpPortRange().replace(",", "-"));
        }

        if (!ObjectUtils.isEmpty(mediaServerItem.getRecordPath())) {
            File recordPathFile = new File(mediaServerItem.getRecordPath());
            param.put("protocol.mp4_save_path", recordPathFile.getParentFile().getPath());
            param.put("protocol.downloadRoot", recordPathFile.getParentFile().getPath());
            param.put("record.appName", recordPathFile.getName());
        }

        JSONObject responseJSON = zlmresTfulUtils.setServerConfig(mediaServerItem, param);

        if (responseJSON != null && responseJSON.getInteger("code") == 0) {
            if (restart) {
                logger.info("[媒体服务节点] 设置成功,开始重启以保证配置生效 {} -> {}:{}",
                        mediaServerItem.getId(), mediaServerItem.getIp(), mediaServerItem.getHttpPort());
                zlmresTfulUtils.restartServer(mediaServerItem);
            }else {
                logger.info("[媒体服务节点] 设置成功 {} -> {}:{}",
                        mediaServerItem.getId(), mediaServerItem.getIp(), mediaServerItem.getHttpPort());
            }
        }else {
            logger.info("[媒体服务节点] 设置媒体服务节点失败 {} -> {}:{}",
                    mediaServerItem.getId(), mediaServerItem.getIp(), mediaServerItem.getHttpPort());
        }
    }

}
