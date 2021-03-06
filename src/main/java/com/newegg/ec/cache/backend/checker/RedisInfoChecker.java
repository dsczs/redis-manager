package com.newegg.ec.cache.backend.checker;

import com.newegg.ec.cache.app.dao.IClusterCheckLogDao;
import com.newegg.ec.cache.app.dao.IClusterCheckRuleDao;
import com.newegg.ec.cache.app.dao.IClusterDao;
import com.newegg.ec.cache.app.dao.INodeInfoDao;
import com.newegg.ec.cache.app.model.Cluster;
import com.newegg.ec.cache.app.model.ClusterCheckLog;
import com.newegg.ec.cache.app.model.ClusterCheckRule;
import com.newegg.ec.cache.app.model.Common;
import com.newegg.ec.cache.app.model.NodeInfo;
import com.newegg.ec.cache.app.util.CommonUtil;
import com.newegg.ec.cache.app.util.DateUtil;
import com.newegg.ec.cache.app.util.JedisUtil;
import com.newegg.ec.cache.app.util.MathExpressionCalculateUtil;
import com.newegg.ec.cache.app.util.httpclient.HttpClientUtil;
import com.newegg.ec.cache.core.logger.CommonLogger;
import net.sf.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import redis.clients.util.Slowlog;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by lf52 on 2018/4/27.
 * 根据用户配置的redis check规则生成预警信息 && slow log check信息
 */
@Component
@PropertySource("classpath:config/schedule.properties")
public class RedisInfoChecker {

    private static final CommonLogger logger = new CommonLogger(RedisInfoChecker.class);
    private static ExecutorService pool = Executors.newFixedThreadPool(200);

    @Resource
    private IClusterCheckRuleDao checkRuleDao;
    @Resource
    private IClusterCheckLogDao checkLogDao;
    @Resource
    private INodeInfoDao infoDao;
    @Resource
    private IClusterDao clusterDao;

    @Value("${spring.wechat.alarm.url}")
    private String wechatUrl;

    @Value("${spring.wechat.alarm.roleId}")
    private String roleId;

    /**
     * 格式化NodeInfo数据
     *
     * @param nodeInfo
     * @return
     */
    private static Map<String, Object> formatNodeInfo(NodeInfo nodeInfo) {
        Map<String, Object> map = new HashMap<>();
        map.put("connectedClients", nodeInfo.getConnectedClients());
        map.put("blockedClients", nodeInfo.getBlockedClients());
        map.put("rejectedConnections", nodeInfo.getRejectedConnections());
        map.put("used_memory", nodeInfo.getUsedMemory());
        map.put("mem_fragmentation_ratio", nodeInfo.getMemFragmentationRatio());
        map.put("usedCpuSys", nodeInfo.getUsedCpuSys());
        map.put("totalKeys", nodeInfo.getTotalKeys());
        map.put("expireKeys", nodeInfo.getExpires());
        map.put("instantaneous_input_kbps", nodeInfo.getInstantaneousInputKbps());
        map.put("instantaneous_output_kbps", nodeInfo.getInstantaneousOutputKbps());
        map.put("responseTime", nodeInfo.getResponseTime());
        return map;
    }

    /**
     * 分析10分钟内的warninglog，每个集群超过总共超过3封就发送微信alarm消息
     */
    @Scheduled(fixedRateString = "${schedule.wechat.alarm}", initialDelay = 1000 * 60)
    public void WeChatEarlyWarning() {
        if (StringUtils.isEmpty(wechatUrl)) {
            return;
        }
        List<Cluster> clusterList = clusterDao.getClusterList(null);
        long time = DateUtil.getBeforeMinutesTime(10);
        for (Cluster cluster : clusterList) {
            pool.execute(new WeChatEarlyWarningTask(cluster, time));
        }

    }

    /**
     * 2min按配置规则检查一次cluster情况
     */
    @Scheduled(fixedRateString = "${schedule.redischeck.warnning}", initialDelay = 1000 * 60)
    public void warningCheck() {
        List<Cluster> clusterList = clusterDao.getClusterList(null);
        for (Cluster cluster : clusterList) {
            pool.execute(new WarnningCheckTask(cluster));
        }
    }

    /**
     * 30min 检查一次slowlog 情况
     */
    @Scheduled(fixedRateString = "${schedule.redischeck.slowlog}", initialDelay = 1000 * 60)
    public void slowLogCheck() {
        List<Cluster> clusterList = clusterDao.getClusterList(null);
        long time = DateUtil.getBeforeHourTime(1);
        for (Cluster cluster : clusterList) {
            pool.execute(new SlowLogCheckTask(cluster, time));
        }
    }

    /**
     * 每个小时清理一次日志，每次只保留最近12小时的的日志
     */
    @Scheduled(cron = "${schedule.redischeck.deletelogs}")
    public void LogsDelete() {
        long time = DateUtil.getBeforeHourTime(12);
        Map<String, Object> param = new HashMap();
        param.put("updateTime", time);
        checkLogDao.delLogs(param);
    }

    /**
     * Warnning Check Task
     */
    class WarnningCheckTask implements Runnable {

        private Cluster cluster;

        public WarnningCheckTask(Cluster cluster) {
            this.cluster = cluster;
        }

        @Override
        public void run() {
            //获取当前cluster所有配置的rule
            int cluster_id = cluster.getId();
            String clusterName = cluster.getClusterName();
            String host = cluster.getAddress().split(",")[0];
            String ip = host.split(":")[0];
            int port = Integer.parseInt(host.split(":")[1]);
            List<ClusterCheckRule> ruleList = checkRuleDao.getClusterRuleList(cluster_id + "");

            //获取每个cluster所有的node
            List<Map<String, String>> nodeList = null;
            try {
                nodeList = JedisUtil.nodeList(ip, port);
            } catch (Exception e) {
                logger.error("Node " + host + " can not get nodelist", e);
            }
            for (ClusterCheckRule rule : ruleList) {
                String formula = rule.getFormula();
                for (Map<String, String> node : nodeList) {
                    NodeInfo nodeInfo = infoDao.getLastNodeInfo(Common.NODE_INFO_TABLE_FORMAT + cluster_id, 0, DateUtil.getTime(), node.get("ip") + ":" + node.get("port"));
                    if (nodeInfo != null) {
                        Map<String, Object> nodeInfoMap = formatNodeInfo(nodeInfo);
                        try {
                            boolean isWarning = Boolean.parseBoolean(String.valueOf(MathExpressionCalculateUtil.calculate(formula, nodeInfoMap)));
                            if (isWarning) {
                                ClusterCheckLog log = new ClusterCheckLog();
                                log.setId(CommonUtil.getUuid());
                                log.setClusterId(cluster.getId() + "");
                                log.setNodeId(node.get("ip") + ":" + node.get("port"));
                                log.setFormula(formula);
                                log.setLogInfo(MathExpressionCalculateUtil.getRuleDataStr(formula, nodeInfoMap));
                                log.setLogType(ClusterCheckLog.LogType.warnlog);
                                log.setUpdateTime(DateUtil.getTime());
                                log.setDescription(rule.getDescription());
                                checkLogDao.addClusterCheckLog(log);
                            }
                        } catch (Exception e) {
                            logger.error(rule.getClusterId() + "\t" + formula + "\t规则出错", e);
                            continue;
                        }
                    }
                }
            }
        }
    }

    /**
     * Warnning Check Task
     * 取最近2000条的slowlog日志，如果超过100条的slowlog发生在最近一小时，则记录一条slowlog报警日志
     */
    class SlowLogCheckTask implements Runnable {

        private Cluster cluster;
        private long time;

        public SlowLogCheckTask(Cluster cluster, long time) {
            this.cluster = cluster;
            this.time = time;
        }

        @Override
        public void run() {
            int num = 0;
            String host = cluster.getAddress().split(",")[0];
            String ip = host.split(":")[0];
            int port = Integer.parseInt(host.split(":")[1]);
            try {
                List<Slowlog> list = JedisUtil.getSlowLog(ip, port, 2000);
                for (Slowlog slowlog : list) {
                    if (slowlog.getTimeStamp() > time) {
                        num++;
                    }
                }
                if (num >= 100) {
                    ClusterCheckLog log = new ClusterCheckLog();
                    log.setId(CommonUtil.getUuid());
                    log.setClusterId(cluster.getClusterName());
                    log.setDescription("Redis slow logs warnning");
                    log.setLogInfo("There are more than 100 slowlogs in last 1 hour on Cluster " + cluster.getClusterName());
                    log.setLogType(ClusterCheckLog.LogType.slowlog);
                    log.setUpdateTime(DateUtil.getTime());
                    checkLogDao.addClusterCheckLog(log);
                }
            } catch (Exception e) {
                logger.error("Node " + host + " check slow log error", e);
            }

        }
    }

    class WeChatEarlyWarningTask implements Runnable {
        private Cluster cluster;
        private long updateTime;

        public WeChatEarlyWarningTask(Cluster cluster, long updateTime) {
            this.cluster = cluster;
            this.updateTime = updateTime;
        }

        @Override
        public void run() {

            //按规则检查日志
            List<ClusterCheckRule> ruleList = checkRuleDao.getClusterRuleList(cluster.getId() + "");
            for (ClusterCheckRule rule : ruleList) {
                String formula = rule.getFormula();
                Map<String, Object> param = new HashMap();
                param.put("clusterId", cluster.getId());
                param.put("updateTime", updateTime);
                param.put("formula", formula);
                int warnsize = checkLogDao.getClusterCheckLogs(param).size();
                if (warnsize > 0) {
                    //发微信
                    JSONObject params = new JSONObject();
                    params.put("metric", "1");
                    params.put("metricValue", "2");
                    params.put("roleId", roleId);
                    params.put("clientId", cluster.getClusterName());
                    params.put("roleName", cluster.getClusterName() + ":" + formula);
                    params.put("errorMessage", "Hello All, " + cluster.getClusterName() + ":" + formula + " Redis Cluster Alarm Log In Last 10 Mins,Please Check !");
                    try {
                        String response = HttpClientUtil.getPostResponse(wechatUrl, params);
                        logger.info("wechat response: " + response);
                    } catch (IOException e) {
                        logger.error("Send Alarm Info To WeChat Error ", e);
                    }
                }
            }
            ;
        }
    }


}
