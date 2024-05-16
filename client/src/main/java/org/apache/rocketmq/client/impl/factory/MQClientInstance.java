/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.client.impl.factory;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.client.ClientConfig;
import org.apache.rocketmq.client.admin.MQAdminExtInner;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.impl.ClientRemotingProcessor;
import org.apache.rocketmq.client.impl.FindBrokerResult;
import org.apache.rocketmq.client.impl.MQAdminImpl;
import org.apache.rocketmq.client.impl.MQClientAPIImpl;
import org.apache.rocketmq.client.impl.MQClientManager;
import org.apache.rocketmq.client.impl.consumer.DefaultMQPullConsumerImpl;
import org.apache.rocketmq.client.impl.consumer.DefaultMQPushConsumerImpl;
import org.apache.rocketmq.client.impl.consumer.MQConsumerInner;
import org.apache.rocketmq.client.impl.consumer.ProcessQueue;
import org.apache.rocketmq.client.impl.consumer.PullMessageService;
import org.apache.rocketmq.client.impl.consumer.RebalanceService;
import org.apache.rocketmq.client.impl.producer.DefaultMQProducerImpl;
import org.apache.rocketmq.client.impl.producer.MQProducerInner;
import org.apache.rocketmq.client.impl.producer.TopicPublishInfo;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.stat.ConsumerStatsManager;
import org.apache.rocketmq.common.MQVersion;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.ServiceState;
import org.apache.rocketmq.common.UtilAll;
import org.apache.rocketmq.common.constant.PermName;
import org.apache.rocketmq.common.filter.ExpressionType;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.common.message.MessageQueueAssignment;
import org.apache.rocketmq.common.topic.TopicValidator;
import org.apache.rocketmq.remoting.RPCHook;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.apache.rocketmq.remoting.netty.NettyClientConfig;
import org.apache.rocketmq.remoting.protocol.NamespaceUtil;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.remoting.protocol.body.ConsumeMessageDirectlyResult;
import org.apache.rocketmq.remoting.protocol.body.ConsumerRunningInfo;
import org.apache.rocketmq.remoting.protocol.heartbeat.ConsumeType;
import org.apache.rocketmq.remoting.protocol.heartbeat.ConsumerData;
import org.apache.rocketmq.remoting.protocol.heartbeat.HeartbeatData;
import org.apache.rocketmq.remoting.protocol.heartbeat.MessageModel;
import org.apache.rocketmq.remoting.protocol.heartbeat.ProducerData;
import org.apache.rocketmq.remoting.protocol.heartbeat.SubscriptionData;
import org.apache.rocketmq.remoting.protocol.route.BrokerData;
import org.apache.rocketmq.remoting.protocol.route.QueueData;
import org.apache.rocketmq.remoting.protocol.route.TopicRouteData;
import org.apache.rocketmq.logging.org.slf4j.Logger;
import org.apache.rocketmq.logging.org.slf4j.LoggerFactory;

import static org.apache.rocketmq.remoting.rpc.ClientMetadata.topicRouteData2EndpointsForStaticTopic;

public class MQClientInstance {
    private final static long LOCK_TIMEOUT_MILLIS = 3000;
    private final static Logger log = LoggerFactory.getLogger(MQClientInstance.class);
    private final ClientConfig clientConfig;
    private final String clientId;
    private final long bootTimestamp = System.currentTimeMillis();

    /**
     * The container of the producer in the current client. The key is the name of producerGroup.
     */
    private final ConcurrentMap<String, MQProducerInner> producerTable = new ConcurrentHashMap<>();

    /**
     * The container of the consumer in the current client. The key is the name of consumerGroup.
     */
    private final ConcurrentMap<String, MQConsumerInner> consumerTable = new ConcurrentHashMap<>();

    /**
     * The container of the adminExt in the current client. The key is the name of adminExtGroup.
     */
    private final ConcurrentMap<String, MQAdminExtInner> adminExtTable = new ConcurrentHashMap<>();
    private final NettyClientConfig nettyClientConfig;
    private final MQClientAPIImpl mQClientAPIImpl;
    private final MQAdminImpl mQAdminImpl;
    private final ConcurrentMap<String/* Topic */, TopicRouteData> topicRouteTable = new ConcurrentHashMap<>();
    private final ConcurrentMap<String/* Topic */, ConcurrentMap<MessageQueue, String/*brokerName*/>> topicEndPointsTable = new ConcurrentHashMap<>();
    private final Lock lockNamesrv = new ReentrantLock();
    private final Lock lockHeartbeat = new ReentrantLock();

    /**
     * The container which stores the brokerClusterInfo. The key of the map is the brokerCluster name.
     * And the value is the broker instance list that belongs to the broker cluster.
     * For the sub map, the key is the id of single broker instance, and the value is the address.
     */
    private final ConcurrentMap<String, HashMap<Long, String>> brokerAddrTable = new ConcurrentHashMap<>();

    private final ConcurrentMap<String/* Broker Name */, HashMap<String/* address */, Integer>> brokerVersionTable = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "MQClientFactoryScheduledThread"));
    private final PullMessageService pullMessageService;
    private final RebalanceService rebalanceService;
    private final DefaultMQProducer defaultMQProducer;
    private final ConsumerStatsManager consumerStatsManager;
    private final AtomicLong sendHeartbeatTimesTotal = new AtomicLong(0);
    private ServiceState serviceState = ServiceState.CREATE_JUST;
    private final Random random = new Random();

    public MQClientInstance(ClientConfig clientConfig, int instanceIndex, String clientId) {
        this(clientConfig, instanceIndex, clientId, null);
    }

    public MQClientInstance(ClientConfig clientConfig, int instanceIndex, String clientId, RPCHook rpcHook) {
        this.clientConfig = clientConfig;
        //创建netty客户端配置类实例
        this.nettyClientConfig = new NettyClientConfig();
        this.nettyClientConfig.setClientCallbackExecutorThreads(clientConfig.getClientCallbackExecutorThreads());
        this.nettyClientConfig.setUseTLS(clientConfig.isUseTLS());
        this.nettyClientConfig.setSocksProxyConfig(clientConfig.getSocksProxyConfig());
        //客户端请求处理器
        ClientRemotingProcessor clientRemotingProcessor = new ClientRemotingProcessor(this);
        //创建客户端远程通信API实现类的实例，内部持有一个remotingClient
        this.mQClientAPIImpl = new MQClientAPIImpl(this.nettyClientConfig, clientRemotingProcessor, rpcHook, clientConfig);
        //更新namesrvAddr，即根据“;”将namesrvAddr字符串拆分为namesrvAddrList集合
        if (this.clientConfig.getNamesrvAddr() != null) {
            this.mQClientAPIImpl.updateNameServerAddressList(this.clientConfig.getNamesrvAddr());
            log.info("user specified name server address: {}", this.clientConfig.getNamesrvAddr());
        }
        //客户端id
        this.clientId = clientId;
        //MQ的admin控制台操作的实现
        this.mQAdminImpl = new MQAdminImpl(this);
        //push模式下，拉取消息的服务
        this.pullMessageService = new PullMessageService(this);
        //消息消费的负载均衡服务
        this.rebalanceService = new RebalanceService(this);
        //客户端内部的生产者，该生产者用于发送消息回退请求
        this.defaultMQProducer = new DefaultMQProducer(MixAll.CLIENT_INNER_PRODUCER_GROUP);
        this.defaultMQProducer.resetClientConfig(clientConfig);
        //消费者状态管理器
        this.consumerStatsManager = new ConsumerStatsManager(this.scheduledExecutorService);

        log.info("Created a new client Instance, InstanceIndex:{}, ClientID:{}, ClientConfig:{}, ClientVersion:{}, SerializerType:{}",
                instanceIndex,
            this.clientId,
            this.clientConfig,
            MQVersion.getVersionDesc(MQVersion.CURRENT_VERSION), RemotingCommand.getSerializeTypeConfigInThisServer());
    }

    public static TopicPublishInfo topicRouteData2TopicPublishInfo(final String topic, final TopicRouteData route) {
        TopicPublishInfo info = new TopicPublishInfo();
        // TO DO should check the usage of raw route, it is better to remove such field
        info.setTopicRouteData(route);
        if (route.getOrderTopicConf() != null && route.getOrderTopicConf().length() > 0) {
            String[] brokers = route.getOrderTopicConf().split(";");
            for (String broker : brokers) {
                String[] item = broker.split(":");
                int nums = Integer.parseInt(item[1]);
                for (int i = 0; i < nums; i++) {
                    MessageQueue mq = new MessageQueue(topic, item[0], i);
                    info.getMessageQueueList().add(mq);
                }
            }

            info.setOrderTopic(true);
        } else if (route.getOrderTopicConf() == null
                && route.getTopicQueueMappingByBroker() != null
                && !route.getTopicQueueMappingByBroker().isEmpty()) {
            info.setOrderTopic(false);
            ConcurrentMap<MessageQueue, String> mqEndPoints = topicRouteData2EndpointsForStaticTopic(topic, route);
            info.getMessageQueueList().addAll(mqEndPoints.keySet());
            info.getMessageQueueList().sort((mq1, mq2) -> MixAll.compareInteger(mq1.getQueueId(), mq2.getQueueId()));
        } else {
            List<QueueData> qds = route.getQueueDatas();
            Collections.sort(qds);
            for (QueueData qd : qds) {
                if (PermName.isWriteable(qd.getPerm())) {
                    BrokerData brokerData = null;
                    for (BrokerData bd : route.getBrokerDatas()) {
                        if (bd.getBrokerName().equals(qd.getBrokerName())) {
                            brokerData = bd;
                            break;
                        }
                    }

                    if (null == brokerData) {
                        continue;
                    }

                    if (!brokerData.getBrokerAddrs().containsKey(MixAll.MASTER_ID)) {
                        continue;
                    }

                    for (int i = 0; i < qd.getWriteQueueNums(); i++) {
                        MessageQueue mq = new MessageQueue(topic, qd.getBrokerName(), i);
                        info.getMessageQueueList().add(mq);
                    }
                }
            }

            info.setOrderTopic(false);
        }

        return info;
    }

    public static Set<MessageQueue> topicRouteData2TopicSubscribeInfo(final String topic, final TopicRouteData route) {
        Set<MessageQueue> mqList = new HashSet<>();
        if (route.getTopicQueueMappingByBroker() != null
                && !route.getTopicQueueMappingByBroker().isEmpty()) {
            ConcurrentMap<MessageQueue, String> mqEndPoints = topicRouteData2EndpointsForStaticTopic(topic, route);
            return mqEndPoints.keySet();
        }
        List<QueueData> qds = route.getQueueDatas();
        for (QueueData qd : qds) {
            if (PermName.isReadable(qd.getPerm())) {
                for (int i = 0; i < qd.getReadQueueNums(); i++) {
                    MessageQueue mq = new MessageQueue(topic, qd.getBrokerName(), i);
                    mqList.add(mq);
                }
            }
        }

        return mqList;
    }

    public void start() throws MQClientException {

        synchronized (this) {
            switch (this.serviceState) {
                case CREATE_JUST:
                    this.serviceState = ServiceState.START_FAILED;
                    // If not specified,looking address from name server
                    // namesrvAddr 是空，则从获取地址
                    if (null == this.clientConfig.getNamesrvAddr()) {
                        this.mQClientAPIImpl.fetchNameServerAddr();
                    }
                    // Start request-response channel
                    //该方法创建一个netty客户端，以及两个定时任务和netty事件监听器，注意并没有真正执行netty客户端的connect连接操作。
                    this.mQClientAPIImpl.start();
                    // Start various schedule tasks
                    // tartScheduledTask启动各种定时任务
                    this.startScheduledTask();
                    // Start pull service
                    this.pullMessageService.start();
                    // Start rebalance service
                    this.rebalanceService.start();
                    // Start push service
                    this.defaultMQProducer.getDefaultMQProducerImpl().start(false);
                    log.info("the client factory [{}] start OK", this.clientId);
                    this.serviceState = ServiceState.RUNNING;
                    break;
                case START_FAILED:
                    throw new MQClientException("The Factory object[" + this.getClientId() + "] has been created before, and failed.", null);
                default:
                    break;
            }
        }
    }

    /**
     * 启动各种定时任务
     */
    private void startScheduledTask() {
        /*
         * 1 如果没有手动指定namesrvAddr，那么每隔2m从nameServer地址服务器拉取最新的nameServer地址并更新
         * 要想动态更新nameServer地址，需要指定一个地址服务器的url
         */
        if (null == this.clientConfig.getNamesrvAddr()) {
            this.scheduledExecutorService.scheduleAtFixedRate(() -> {
                try {
                    MQClientInstance.this.mQClientAPIImpl.fetchNameServerAddr();
                } catch (Exception e) {
                    log.error("ScheduledTask fetchNameServerAddr exception", e);
                }
            }, 1000 * 10, 1000 * 60 * 2, TimeUnit.MILLISECONDS);
        }

        /*
        * 2 每隔30S尝试从nameServer更新topic路由信息
        */
        this.scheduledExecutorService.scheduleAtFixedRate(() -> {
            try {
                MQClientInstance.this.updateTopicRouteInfoFromNameServer();
            } catch (Exception e) {
                log.error("ScheduledTask updateTopicRouteInfoFromNameServer exception", e);
            }
        }, 10, this.clientConfig.getPollNameServerInterval(), TimeUnit.MILLISECONDS);

        /*
         * 3 每隔30S尝试清除无效的broker信息，以及发送心跳信息给所有broker
         */
        this.scheduledExecutorService.scheduleAtFixedRate(() -> {
            try {
                MQClientInstance.this.cleanOfflineBroker();
                MQClientInstance.this.sendHeartbeatToAllBrokerWithLock();
            } catch (Exception e) {
                log.error("ScheduledTask sendHeartbeatToAllBroker exception", e);
            }
        }, 1000, this.clientConfig.getHeartbeatBrokerInterval(), TimeUnit.MILLISECONDS);

        /*
         * 4 每隔5S尝试持久化消费者偏移量，即消费进度
         * 广播消费模式下持久化到本地，集群消费模式下推送到broker端
         */
        this.scheduledExecutorService.scheduleAtFixedRate(() -> {
            try {
                MQClientInstance.this.persistAllConsumerOffset();
            } catch (Exception e) {
                log.error("ScheduledTask persistAllConsumerOffset exception", e);
            }
        }, 1000 * 10, this.clientConfig.getPersistConsumerOffsetInterval(), TimeUnit.MILLISECONDS);

        /*
         * 5 每隔1min尝试调整push模式的消费线程池的线程数量
         */
        this.scheduledExecutorService.scheduleAtFixedRate(() -> {
            try {
                MQClientInstance.this.adjustThreadPool();
            } catch (Exception e) {
                log.error("ScheduledTask adjustThreadPool exception", e);
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    public String getClientId() {
        return clientId;
    }

    /**
     * MQClientInstance的方法
     * 从nameServer拉取并更新topic信息
     */
    public void updateTopicRouteInfoFromNameServer() {
        //使用set集合存储topic，去除重复的数据
        Set<String> topicList = new HashSet<>();

        // Consumer
        //从consumerTable中获取所有的内部生产者DefaultMQPushConsumerImpl的实例，然后获取内部的rebalanceImpl#subscriptionInner集合中的topic
        {
            for (Entry<String, MQConsumerInner> entry : this.consumerTable.entrySet()) {
                MQConsumerInner impl = entry.getValue();
                if (impl != null) {
                    Set<SubscriptionData> subList = impl.subscriptions();
                    if (subList != null) {
                        for (SubscriptionData subData : subList) {
                            topicList.add(subData.getTopic());
                        }
                    }
                }
            }
        }

        // Producer
        //从producerTable中获取所有的内部生产者DefaultMQProducerImpl的实例，然后获取内部的topicPublishInfoTable集合中的topic
        {
            for (Entry<String, MQProducerInner> entry : this.producerTable.entrySet()) {
                MQProducerInner impl = entry.getValue();
                if (impl != null) {
                    Set<String> lst = impl.getPublishTopicList();
                    topicList.addAll(lst);
                }
            }
        }
        /*
         * 遍历每一个topic，从nameServer获取topic的路由信息
         * 然后比较topic是否改变，如果改变了，对客户端本地保存的路由信息进行更新
         */
        for (String topic : topicList) {
            this.updateTopicRouteInfoFromNameServer(topic);
        }
    }

    public Map<MessageQueue, Long> parseOffsetTableFromBroker(Map<MessageQueue, Long> offsetTable, String namespace) {
        HashMap<MessageQueue, Long> newOffsetTable = new HashMap<>(offsetTable.size(), 1);
        if (StringUtils.isNotEmpty(namespace)) {
            for (Entry<MessageQueue, Long> entry : offsetTable.entrySet()) {
                MessageQueue queue = entry.getKey();
                queue.setTopic(NamespaceUtil.withoutNamespace(queue.getTopic(), namespace));
                newOffsetTable.put(queue, entry.getValue());
            }
        } else {
            newOffsetTable.putAll(offsetTable);
        }

        return newOffsetTable;
    }

    /**
     * 清除下线的broker
     * 该方法会遍历并且更新brokerAddrTable这个map集合，
     * 该集合类型为ConcurrentMap<String, HashMap<Long, String>> key：brokerName，value：brokerAddrTable《brokerId，brokerAddr》
     * 获取每一个address，然后去本地路由信息集合topicRouteTable中查找判断broker地址是否存在于topicRouteTable的任意一个topic的路由信息中，
     * 如果不存在，则表示该broker已下线，那么清除该broker地址，否则保留。如果brokerAddrTable中的value集合也是空的，那么直接删除键值对
     * Remove offline broker
     */
    private void cleanOfflineBroker() {
        try {
            //加锁
            if (this.lockNamesrv.tryLock(LOCK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                try {
                    ConcurrentHashMap<String, HashMap<Long, String>> updatedTable = new ConcurrentHashMap<>(this.brokerAddrTable.size(), 1);

                    Iterator<Entry<String, HashMap<Long, String>>> itBrokerTable = this.brokerAddrTable.entrySet().iterator();
                    //遍历brokerAddrTable
                    while (itBrokerTable.hasNext()) {
                        Entry<String, HashMap<Long, String>> entry = itBrokerTable.next();
                        String brokerName = entry.getKey();
                        HashMap<Long, String> oneTable = entry.getValue();

                        HashMap<Long, String> cloneAddrTable = new HashMap<>(oneTable.size(), 1);
                        cloneAddrTable.putAll(oneTable);

                        Iterator<Entry<Long, String>> it = cloneAddrTable.entrySet().iterator();
                        while (it.hasNext()) {
                            Entry<Long, String> ee = it.next();
                            String addr = ee.getValue();
                            //判断broker地址是否存在于topicRouteTable的任意一个topic的路由信息中，如果不存在则移除该broker地址
                            if (!this.isBrokerAddrExistInTopicRouteTable(addr)) {
                                it.remove();
                                log.info("the broker addr[{} {}] is offline, remove it", brokerName, addr);
                            }
                        }
                        //如果集合为空，则移除brokerAddrTable的此项键值对
                        if (cloneAddrTable.isEmpty()) {
                            itBrokerTable.remove();
                            log.info("the broker[{}] name's host is offline, remove it", brokerName);
                        } else {
                            //否则更新剩下的broker地址信息
                            updatedTable.put(brokerName, cloneAddrTable);
                        }
                    }

                    //更新brokerAddrTable的信息
                    if (!updatedTable.isEmpty()) {
                        this.brokerAddrTable.putAll(updatedTable);
                    }
                } finally {
                    this.lockNamesrv.unlock();
                }
        } catch (InterruptedException e) {
            log.warn("cleanOfflineBroker Exception", e);
        }
    }

    public void checkClientInBroker() throws MQClientException {

        for (Entry<String, MQConsumerInner> entry : this.consumerTable.entrySet()) {
            Set<SubscriptionData> subscriptionInner = entry.getValue().subscriptions();
            if (subscriptionInner == null || subscriptionInner.isEmpty()) {
                return;
            }

            for (SubscriptionData subscriptionData : subscriptionInner) {
                if (ExpressionType.isTagType(subscriptionData.getExpressionType())) {
                    continue;
                }
                // may need to check one broker every cluster...
                // assume that the configs of every broker in cluster are the same.
                String addr = findBrokerAddrByTopic(subscriptionData.getTopic());

                if (addr != null) {
                    try {
                        this.getMQClientAPIImpl().checkClientInBroker(
                                addr, entry.getKey(), this.clientId, subscriptionData, clientConfig.getMqClientApiTimeout()
                        );
                    } catch (Exception e) {
                        if (e instanceof MQClientException) {
                            throw (MQClientException) e;
                        } else {
                            throw new MQClientException("Check client in broker error, maybe because you use "
                                    + subscriptionData.getExpressionType() + " to filter message, but server has not been upgraded to support!"
                                    + "This error would not affect the launch of consumer, but may has impact on message receiving if you " +
                                    "have use the new features which are not supported by server, please check the log!", e);
                        }
                    }
                }
            }
        }
    }

    /**
     * MQClientInstance的方法
     * 发送心跳包给所有broker
     * 每隔30s向所有broker发送心跳包的定时任务方法。
     * 客户的consumer和producer都是通过该定时任务发送心跳数据包的。在其他地方也会主动调用一次该方法，
     * 例如DefaultMQProducerImpl、DefaultMQPushConsumerImpl等类的start方法的结尾都会主动调用一次该方法
     */
    public void sendHeartbeatToAllBrokerWithLock() {
        //加锁
        if (this.lockHeartbeat.tryLock()) {
            try {
                //发送心跳包给所有broker
                this.sendHeartbeatToAllBroker();
                //上传过滤类到Broker对应的所有Filtersrv，push模式消费使用
                this.uploadFilterClassSource();
            } catch (final Exception e) {
                log.error("sendHeartbeatToAllBroker exception", e);
            } finally {
                this.lockHeartbeat.unlock();
            }
        } else {
            log.warn("lock heartBeat, but failed. [{}]", this.clientId);
        }
    }

    private void persistAllConsumerOffset() {
        for (Entry<String, MQConsumerInner> entry : this.consumerTable.entrySet()) {
            MQConsumerInner impl = entry.getValue();
            impl.persistConsumerOffset();
        }
    }

    public void adjustThreadPool() {
        for (Entry<String, MQConsumerInner> entry : this.consumerTable.entrySet()) {
            MQConsumerInner impl = entry.getValue();
            if (impl != null) {
                try {
                    if (impl instanceof DefaultMQPushConsumerImpl) {
                        DefaultMQPushConsumerImpl dmq = (DefaultMQPushConsumerImpl) impl;
                        dmq.adjustThreadPool();
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    public boolean updateTopicRouteInfoFromNameServer(final String topic) {
        return updateTopicRouteInfoFromNameServer(topic, false, null);
    }

    /**
     * MQClientInstance的方法
     * broker地址是否存在于topicRouteTable的任意一个topic的路由信息中
     * @param addr
     * @return
     */
    private boolean isBrokerAddrExistInTopicRouteTable(final String addr) {
        //遍历topicRouteTable
        for (Entry<String, TopicRouteData> entry : this.topicRouteTable.entrySet()) {
            TopicRouteData topicRouteData = entry.getValue();
            List<BrokerData> bds = topicRouteData.getBrokerDatas();
            for (BrokerData bd : bds) {
                //broker地址是否存在于topicRouteTable的任意一个topic的路由信息中
                if (bd.getBrokerAddrs() != null) {
                    boolean exist = bd.getBrokerAddrs().containsValue(addr);
                    if (exist)
                        return true;
                }
            }
        }

        return false;
    }
    /**
     * MQClientInstance的方法
     * 给所有broker发送心跳包
     * 用于给所有broker发送心跳包，首先会通过prepareHeartbeatData方法准备心跳数据包，
     * 如果数据包中没有任何生产者和消费者的信息，那么不会发送心跳包，直接返回
     * 然后会遍历brokerAddrTable中的broker地址，开始循环发送心跳包。
     * 此时还需要判断，如果数据包中没有消费者的信息并且当前broker不是Master节点，那么无需向该broker发送心跳包，
     * 因为此时该应用中只有生产者启动，而生产者只能给Master发送消息数据。
     * 但是如果该应用启动了消费者，由于消费者可以从Master和Slave拉取消息，因此会向所有broker发送心跳包。
     */
    private void sendHeartbeatToAllBroker() {
        /*
         * 1 准备心跳数据包
         */
        final HeartbeatData heartbeatData = this.prepareHeartbeatData();
        //如果没有任何关于生产者和消费者的数据，那么不需要发送心跳包
        final boolean producerEmpty = heartbeatData.getProducerDataSet().isEmpty();
        final boolean consumerEmpty = heartbeatData.getConsumerDataSet().isEmpty();
        if (producerEmpty && consumerEmpty) {
            log.warn("sending heartbeat, but no consumer and no producer. [{}]", this.clientId);
            return;
        }

        // brokerAddrTable地址为空，则不需要发送心跳包
        if (this.brokerAddrTable.isEmpty()) {
            return;
        }
        //发送心跳的次数自增1
        long times = this.sendHeartbeatTimesTotal.getAndIncrement();
        //循环遍历brokerAddrTable
        for (Entry<String, HashMap<Long, String>> brokerClusterInfo : this.brokerAddrTable.entrySet()) {
            String brokerName = brokerClusterInfo.getKey();
            HashMap<Long, String> oneTable = brokerClusterInfo.getValue();
            if (oneTable == null) {
                continue;
            }
            for (Entry<Long, String> singleBrokerInstance : oneTable.entrySet()) {
                Long id = singleBrokerInstance.getKey();
                String addr = singleBrokerInstance.getValue();
                if (addr == null) {
                    continue;
                }
                /*
                 * 2 如果消费者配置为空，并且当前broker节点不是Master，那么跳过当前broker注册，因为producer只需要向和Master维持心跳即可
                 * 如果当前应用中启动了消费者，那么就会向所有的broker注册
                 */
                if (consumerEmpty && MixAll.MASTER_ID != id) {
                    continue;
                }

                try {
                    /*
                     * 3 给broker发送心跳数据包，返回版本
                     */
                    int version = this.mQClientAPIImpl.sendHeartbeat(addr, heartbeatData, clientConfig.getMqClientApiTimeout());
                    if (!this.brokerVersionTable.containsKey(brokerName)) {
                        this.brokerVersionTable.put(brokerName, new HashMap<>(4));
                    }
                    this.brokerVersionTable.get(brokerName).put(addr, version);
                    //每二十次心跳，打印一次日志
                    if (times % 20 == 0) {
                        log.info("send heart beat to broker[{} {} {}] success", brokerName, id, addr);
                        log.info(heartbeatData.toString());
                    }
                } catch (Exception e) {
                    if (this.isBrokerInNameServer(addr)) {
                        log.warn("send heart beat to broker[{} {} {}] failed", brokerName, id, addr, e);
                    } else {
                        log.warn("send heart beat to broker[{} {} {}] exception, because the broker not up, forget it", brokerName,
                                id, addr, e);
                    }
                }
            }
        }
    }

    private void uploadFilterClassSource() {
        for (Entry<String, MQConsumerInner> next : this.consumerTable.entrySet()) {
            MQConsumerInner consumer = next.getValue();
            if (ConsumeType.CONSUME_PASSIVELY != consumer.consumeType()) {
                continue;
            }
            Set<SubscriptionData> subscriptions = consumer.subscriptions();
            for (SubscriptionData sub : subscriptions) {
                if (sub.isClassFilterMode() && sub.getFilterClassSource() != null) {
                    final String consumerGroup = consumer.groupName();
                    final String className = sub.getSubString();
                    final String topic = sub.getTopic();
                    final String filterClassSource = sub.getFilterClassSource();
                    try {
                        this.uploadFilterClassToAllFilterServer(consumerGroup, className, topic, filterClassSource);
                    } catch (Exception e) {
                        log.error("uploadFilterClassToAllFilterServer Exception", e);
                    }
                }
            }
        }
    }

    /**
     * 该方法用于从nameServer拉取并更新topic的路由信息，topic的路由信息由broker上报给nameServer。
     * 从nameSerer拉取到topic路由信息之后，调用topicRouteDataIsChange方法与本地的旧topic路由信息比较看是否更改，
     * 比较的数据包括：topic的队列信息queueDatas，topic的broker信息brokerDatas，顺序topic配置orderTopicConf，消费过滤信息filterServerTable。
     * 当判断需要更新的时候，会更新本地的topic缓存
     * @param topic
     * @param isDefault
     * @param defaultMQProducer
     * @return
     */
    public boolean updateTopicRouteInfoFromNameServer(final String topic, boolean isDefault,
        DefaultMQProducer defaultMQProducer) {
        try {
            //加锁防止并发
            if (this.lockNamesrv.tryLock(LOCK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                try {
                    TopicRouteData topicRouteData;
                    /*
                     * 1 通过默认topic和producer去获取路由信息时走此逻辑
                     */
                    if (isDefault && defaultMQProducer != null) {
                        topicRouteData = this.mQClientAPIImpl.getDefaultTopicRouteInfoFromNameServer(defaultMQProducer.getCreateTopicKey(),
                            clientConfig.getMqClientApiTimeout());
                        if (topicRouteData != null) {
                            //更新队列数据，设置读写队列数量
                            for (QueueData data : topicRouteData.getQueueDatas()) {
                                int queueNums = Math.min(defaultMQProducer.getDefaultTopicQueueNums(), data.getReadQueueNums());
                                data.setReadQueueNums(queueNums);
                                data.setWriteQueueNums(queueNums);
                            }
                        }
                    } else {
                        /*
                         * 2 定时任务会走该逻辑
                         * 从nameServer获取当前指定topic的路由信息
                         */
                        topicRouteData = this.mQClientAPIImpl.getTopicRouteInfoFromNameServer(topic, clientConfig.getMqClientApiTimeout());
                    }
                    /*
                     * 3 如果获取到的路由信息不为空，那么比较看是否需要更新本地路由信息
                     */
                    if (topicRouteData != null) {
                        TopicRouteData old = this.topicRouteTable.get(topic);
                        /*
                         * 4 比较nameServer的topic路由和本地topic路由，看是否不相同
                         */
                        boolean changed = topicRouteData.topicRouteDataChanged(old);
                        if (!changed) {
                            //如果相同，看本地是否还存在当前topic的配置，如果不存在则还是需要更新topic路由
                            changed = this.isNeedUpdateTopicRouteInfo(topic);
                        } else {
                            log.info("the topic[{}] route info changed, old[{}] ,new[{}]", topic, old, topicRouteData);
                        }

                        /*
                         * 5 如果需要更新本地路由信息，那么执行更新操作
                         */
                        if (changed) {
                            /*
                             * 更新更新brokerName到brokerAddr的地址的映射关系，即brokerAddrTable
                             */
                            for (BrokerData bd : topicRouteData.getBrokerDatas()) {
                                this.brokerAddrTable.put(bd.getBrokerName(), bd.getBrokerAddrs());
                            }


                            // Update endpoint map

                            /*
                             * 更新topicRouteTable集合，更新本地topic信息
                             * topicEndPointsTable 存储的是主题（topic）与消息队列（MessageQueue）对应的终端点（String）。
                             * 具体来说，它是一个映射表，将主题与其对应的消息队列的终端点进行关联。
                             * 这个映射表的作用是帮助系统跟踪消息队列所在的终端点，以便在消息传递过程中能够准确地路由到相应的终端
                             */
                            {
                                ConcurrentMap<MessageQueue, String> mqEndPoints = topicRouteData2EndpointsForStaticTopic(topic, topicRouteData);
                                if (!mqEndPoints.isEmpty()) {
                                    topicEndPointsTable.put(topic, mqEndPoints);
                                }
                            }

                            // Update Pub info
                            /*
                             * 更新生产者的producerTable集合，更新MQProducerInner的topicPublishInfoTable属性
                             */
                            {
                                TopicPublishInfo publishInfo = topicRouteData2TopicPublishInfo(topic, topicRouteData);
                                publishInfo.setHaveTopicRouterInfo(true);
                                for (Entry<String, MQProducerInner> entry : this.producerTable.entrySet()) {
                                    MQProducerInner impl = entry.getValue();
                                    if (impl != null) {
                                        impl.updateTopicPublishInfo(topic, publishInfo);
                                    }
                                }
                            }

                            // Update sub info
                            /*
                             * 更新消费者的consumerTable集合，更新MQConsumerInner的rebalanceImpl.topicSubscribeInfoTable属性
                             */
                            if (!consumerTable.isEmpty()) {
                                Set<MessageQueue> subscribeInfo = topicRouteData2TopicSubscribeInfo(topic, topicRouteData);
                                for (Entry<String, MQConsumerInner> entry : this.consumerTable.entrySet()) {
                                    MQConsumerInner impl = entry.getValue();
                                    if (impl != null) {
                                        impl.updateTopicSubscribeInfo(topic, subscribeInfo);
                                    }
                                }
                            }
                            //克隆一个TopicRouteData
                            TopicRouteData cloneTopicRouteData = new TopicRouteData(topicRouteData);
                            log.info("topicRouteTable.put. Topic = {}, TopicRouteData[{}]", topic, cloneTopicRouteData);
                            /*
                             * 更新topicRouteTable集合，更新本地topic信息
                             */
                            this.topicRouteTable.put(topic, cloneTopicRouteData);
                            return true;
                        }
                    } else {
                        log.warn("updateTopicRouteInfoFromNameServer, getTopicRouteInfoFromNameServer return null, Topic: {}. [{}]", topic, this.clientId);
                    }
                } catch (MQClientException e) {
                    if (!topic.startsWith(MixAll.RETRY_GROUP_TOPIC_PREFIX) && !topic.equals(TopicValidator.AUTO_CREATE_TOPIC_KEY_TOPIC)) {
                        log.warn("updateTopicRouteInfoFromNameServer Exception", e);
                    }
                } catch (RemotingException e) {
                    log.error("updateTopicRouteInfoFromNameServer Exception", e);
                    throw new IllegalStateException(e);
                } finally {
                    this.lockNamesrv.unlock();
                }
            } else {
                log.warn("updateTopicRouteInfoFromNameServer tryLock timeout {}ms. [{}]", LOCK_TIMEOUT_MILLIS, this.clientId);
            }
        } catch (InterruptedException e) {
            log.warn("updateTopicRouteInfoFromNameServer Exception", e);
        }

        return false;
    }

    /**
     * 该方法构建心跳数据包，
     * 一个心跳包的内容包括：客户端id、消费者信息集合（消费者组名、消费类型、消费模式、启动消费者时从哪开始消费、订阅信息）、生产者信息集合（生产者组名）
     * @return
     */
    private HeartbeatData prepareHeartbeatData() {
        HeartbeatData heartbeatData = new HeartbeatData();

        // clientID
        heartbeatData.setClientID(this.clientId);

        // Consumer
        // 消费者心跳信息
        for (Map.Entry<String, MQConsumerInner> entry : this.consumerTable.entrySet()) {
            MQConsumerInner impl = entry.getValue();
            if (impl != null) {
                //构建ConsumerData数据
                ConsumerData consumerData = new ConsumerData();
                //消费者组名
                consumerData.setGroupName(impl.groupName());
                //消费类型 pull push
                consumerData.setConsumeType(impl.consumeType());
                //消费模式 集群 广播
                consumerData.setMessageModel(impl.messageModel());
                //启动消费者时从哪开始消费
                consumerData.setConsumeFromWhere(impl.consumeFromWhere());
                //订阅信息，包括过滤消息相关标签、SQL规则
                consumerData.getSubscriptionDataSet().addAll(impl.subscriptions());
                consumerData.setUnitMode(impl.isUnitMode());
                //加入到消费者数据集合中
                heartbeatData.getConsumerDataSet().add(consumerData);
            }
        }

        // Producer
        //生产者心跳信息
        for (Map.Entry<String/* group */, MQProducerInner> entry : this.producerTable.entrySet()) {
            MQProducerInner impl = entry.getValue();
            if (impl != null) {
                ProducerData producerData = new ProducerData();
                //生产者组名
                producerData.setGroupName(entry.getKey());

                heartbeatData.getProducerDataSet().add(producerData);
            }
        }

        return heartbeatData;
    }

    private boolean isBrokerInNameServer(final String brokerAddr) {
        for (Entry<String, TopicRouteData> itNext : this.topicRouteTable.entrySet()) {
            List<BrokerData> brokerDatas = itNext.getValue().getBrokerDatas();
            for (BrokerData bd : brokerDatas) {
                boolean contain = bd.getBrokerAddrs().containsValue(brokerAddr);
                if (contain)
                    return true;
            }
        }

        return false;
    }

    /**
     * This method will be removed in the version 5.0.0,because filterServer was removed,and method
     * <code>subscribe(final String topic, final MessageSelector messageSelector)</code> is recommended.
     */
    @Deprecated
    private void uploadFilterClassToAllFilterServer(final String consumerGroup, final String fullClassName,
        final String topic,
        final String filterClassSource) {
        byte[] classBody = null;
        int classCRC = 0;
        try {
            classBody = filterClassSource.getBytes(MixAll.DEFAULT_CHARSET);
            classCRC = UtilAll.crc32(classBody);
        } catch (Exception e1) {
            log.warn("uploadFilterClassToAllFilterServer Exception, ClassName: {} {}",
                fullClassName,
                UtilAll.exceptionSimpleDesc(e1));
        }

        TopicRouteData topicRouteData = this.topicRouteTable.get(topic);
        if (topicRouteData != null
            && topicRouteData.getFilterServerTable() != null && !topicRouteData.getFilterServerTable().isEmpty()) {
            for (Entry<String, List<String>> next : topicRouteData.getFilterServerTable().entrySet()) {
                List<String> value = next.getValue();
                for (final String fsAddr : value) {
                    try {
                        this.mQClientAPIImpl.registerMessageFilterClass(fsAddr, consumerGroup, topic, fullClassName, classCRC, classBody,
                                5000);

                        log.info("register message class filter to {} OK, ConsumerGroup: {} Topic: {} ClassName: {}", fsAddr, consumerGroup,
                                topic, fullClassName);

                    } catch (Exception e) {
                        log.error("uploadFilterClassToAllFilterServer Exception", e);
                    }
                }
            }
        } else {
            log.warn("register message class filter failed, because no filter server, ConsumerGroup: {} Topic: {} ClassName: {}",
                consumerGroup, topic, fullClassName);
        }
    }

    private boolean isNeedUpdateTopicRouteInfo(final String topic) {
        boolean result = false;
        Iterator<Entry<String, MQProducerInner>> producerIterator = this.producerTable.entrySet().iterator();
        while (producerIterator.hasNext() && !result) {
            Entry<String, MQProducerInner> entry = producerIterator.next();
            MQProducerInner impl = entry.getValue();
            if (impl != null) {
                result = impl.isPublishTopicNeedUpdate(topic);
            }
        }

        if (result) {
            return true;
        }

        Iterator<Entry<String, MQConsumerInner>> consumerIterator = this.consumerTable.entrySet().iterator();
        while (consumerIterator.hasNext() && !result) {
            Entry<String, MQConsumerInner> entry = consumerIterator.next();
            MQConsumerInner impl = entry.getValue();
            if (impl != null) {
                result = impl.isSubscribeTopicNeedUpdate(topic);
            }
        }

        return result;
    }

    public void shutdown() {
        // Consumer
        if (!this.consumerTable.isEmpty())
            return;

        // AdminExt
        if (!this.adminExtTable.isEmpty())
            return;

        // Producer
        if (this.producerTable.size() > 1)
            return;

        synchronized (this) {
            switch (this.serviceState) {
                case RUNNING:
                    this.defaultMQProducer.getDefaultMQProducerImpl().shutdown(false);

                    this.serviceState = ServiceState.SHUTDOWN_ALREADY;
                    this.pullMessageService.shutdown(true);
                    this.scheduledExecutorService.shutdown();
                    this.mQClientAPIImpl.shutdown();
                    this.rebalanceService.shutdown();

                    MQClientManager.getInstance().removeClientFactory(this.clientId);
                    log.info("the client factory [{}] shutdown OK", this.clientId);
                    break;
                case CREATE_JUST:
                case SHUTDOWN_ALREADY:
                default:
                    break;
            }
        }
    }

    public synchronized boolean registerConsumer(final String group, final MQConsumerInner consumer) {
        if (null == group || null == consumer) {
            return false;
        }

        MQConsumerInner prev = this.consumerTable.putIfAbsent(group, consumer);
        if (prev != null) {
            log.warn("the consumer group[" + group + "] exist already.");
            return false;
        }

        return true;
    }

    public synchronized void unregisterConsumer(final String group) {
        this.consumerTable.remove(group);
        this.unregisterClient(null, group);
    }

    private void unregisterClient(final String producerGroup, final String consumerGroup) {
        for (Entry<String, HashMap<Long, String>> brokerClusterInfo : this.brokerAddrTable.entrySet()) {
            String brokerName = brokerClusterInfo.getKey();
            HashMap<Long, String> oneTable = brokerClusterInfo.getValue();

            if (oneTable == null) {
                continue;
            }
            for (Entry<Long, String> singleBrokerInstance : oneTable.entrySet()) {
                String addr = singleBrokerInstance.getValue();
                if (addr != null) {
                    try {
                        this.mQClientAPIImpl.unregisterClient(addr, this.clientId, producerGroup, consumerGroup, clientConfig.getMqClientApiTimeout());
                        log.info("unregister client[Producer: {} Consumer: {}] from broker[{} {} {}] success", producerGroup, consumerGroup, brokerName, singleBrokerInstance.getKey(), addr);
                    } catch (RemotingException e) {
                        log.warn("unregister client RemotingException from broker: {}, {}", addr, e.getMessage());
                    } catch (InterruptedException e) {
                        log.warn("unregister client InterruptedException from broker: {}, {}", addr, e.getMessage());
                    } catch (MQBrokerException e) {
                        log.warn("unregister client MQBrokerException from broker: {}, {}", addr, e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * MQClientInstance的方法
     *
     * @param group    生产者组
     * @param producer 生产者实例
     * @return 是否加入成功
     */
    public synchronized boolean registerProducer(final String group, final DefaultMQProducerImpl producer) {
        //任何一个为null直接返回false
        if (null == group || null == producer) {
            return false;
        }

        //如果生产者组不存在，则添加到producerTable集合中，并返回null，否则返回已存在的producer
        MQProducerInner prev = this.producerTable.putIfAbsent(group, producer);
        if (prev != null) {
            log.warn("the producer group[{}] exist already.", group);
            return false;
        }

        return true;
    }

    public synchronized void unregisterProducer(final String group) {
        this.producerTable.remove(group);
        this.unregisterClient(group, null);
    }

    public boolean registerAdminExt(final String group, final MQAdminExtInner admin) {
        if (null == group || null == admin) {
            return false;
        }

        MQAdminExtInner prev = this.adminExtTable.putIfAbsent(group, admin);
        if (prev != null) {
            log.warn("the admin group[{}] exist already.", group);
            return false;
        }

        return true;
    }

    public void unregisterAdminExt(final String group) {
        this.adminExtTable.remove(group);
    }

    public void rebalanceLater(long delayMillis) {
        if (delayMillis <= 0) {
            this.rebalanceService.wakeup();
        } else {
            this.scheduledExecutorService.schedule(MQClientInstance.this.rebalanceService::wakeup, delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * MQClientInstance的方法
     * 立即重平衡
     */
    public void rebalanceImmediately() {
        //唤醒重平衡服务，立即重平衡
        this.rebalanceService.wakeup();
    }

    public void doRebalance() {
        for (Map.Entry<String, MQConsumerInner> entry : this.consumerTable.entrySet()) {
            MQConsumerInner impl = entry.getValue();
            if (impl != null) {
                try {
                    impl.doRebalance();
                } catch (Throwable e) {
                    log.error("doRebalance exception", e);
                }
            }
        }
    }

    public MQProducerInner selectProducer(final String group) {
        return this.producerTable.get(group);
    }

    public MQConsumerInner selectConsumer(final String group) {
        return this.consumerTable.get(group);
    }

    public String getBrokerNameFromMessageQueue(final MessageQueue mq) {
        if (topicEndPointsTable.get(mq.getTopic()) != null && !topicEndPointsTable.get(mq.getTopic()).isEmpty()) {
            return topicEndPointsTable.get(mq.getTopic()).get(mq);
        }
        return mq.getBrokerName();
    }

    public FindBrokerResult findBrokerAddressInAdmin(final String brokerName) {
        if (brokerName == null) {
            return null;
        }
        String brokerAddr = null;
        boolean slave = false;
        boolean found = false;

        HashMap<Long/* brokerId */, String/* address */> map = this.brokerAddrTable.get(brokerName);
        if (map != null && !map.isEmpty()) {
            for (Map.Entry<Long, String> entry : map.entrySet()) {
                Long id = entry.getKey();
                brokerAddr = entry.getValue();
                if (brokerAddr != null) {
                    found = true;
                    slave = MixAll.MASTER_ID != id;
                    break;

                }
            } // end of for
        }

        if (found) {
            return new FindBrokerResult(brokerAddr, slave, findBrokerVersion(brokerName, brokerAddr));
        }

        return null;
    }

    public String findBrokerAddressInPublish(final String brokerName) {
        if (brokerName == null) {
            return null;
        }
        HashMap<Long/* brokerId */, String/* address */> map = this.brokerAddrTable.get(brokerName);
        if (map != null && !map.isEmpty()) {
            return map.get(MixAll.MASTER_ID);
        }

        return null;
    }

    public FindBrokerResult findBrokerAddressInSubscribe(
        final String brokerName,
        final long brokerId,
        final boolean onlyThisBroker
    ) {
        if (brokerName == null) {
            return null;
        }
        String brokerAddr = null;
        boolean slave = false;
        boolean found = false;

        HashMap<Long/* brokerId */, String/* address */> map = this.brokerAddrTable.get(brokerName);
        if (map != null && !map.isEmpty()) {
            brokerAddr = map.get(brokerId);
            slave = brokerId != MixAll.MASTER_ID;
            found = brokerAddr != null;

            if (!found && slave) {
                brokerAddr = map.get(brokerId + 1);
                found = brokerAddr != null;
            }

            if (!found && !onlyThisBroker) {
                Entry<Long, String> entry = map.entrySet().iterator().next();
                brokerAddr = entry.getValue();
                slave = entry.getKey() != MixAll.MASTER_ID;
                found = true;
            }
        }

        if (found) {
            return new FindBrokerResult(brokerAddr, slave, findBrokerVersion(brokerName, brokerAddr));
        }

        return null;
    }

    private int findBrokerVersion(String brokerName, String brokerAddr) {
        if (this.brokerVersionTable.containsKey(brokerName)) {
            if (this.brokerVersionTable.get(brokerName).containsKey(brokerAddr)) {
                return this.brokerVersionTable.get(brokerName).get(brokerAddr);
            }
        }
        //To do need to fresh the version
        return 0;
    }

    public List<String> findConsumerIdList(final String topic, final String group) {
        String brokerAddr = this.findBrokerAddrByTopic(topic);
        if (null == brokerAddr) {
            this.updateTopicRouteInfoFromNameServer(topic);
            brokerAddr = this.findBrokerAddrByTopic(topic);
        }

        if (null != brokerAddr) {
            try {
                return this.mQClientAPIImpl.getConsumerIdListByGroup(brokerAddr, group, clientConfig.getMqClientApiTimeout());
            } catch (Exception e) {
                log.warn("getConsumerIdListByGroup exception, " + brokerAddr + " " + group, e);
            }
        }

        return null;
    }

    public Set<MessageQueueAssignment> queryAssignment(final String topic, final String consumerGroup,
        final String strategyName, final MessageModel messageModel, int timeout)
        throws RemotingException, InterruptedException, MQBrokerException {
        String brokerAddr = this.findBrokerAddrByTopic(topic);
        if (null == brokerAddr) {
            this.updateTopicRouteInfoFromNameServer(topic);
            brokerAddr = this.findBrokerAddrByTopic(topic);
        }

        if (null != brokerAddr) {
            return this.mQClientAPIImpl.queryAssignment(brokerAddr, topic, consumerGroup, clientId, strategyName,
                messageModel, timeout);
        }

        return null;
    }

    public String findBrokerAddrByTopic(final String topic) {
        TopicRouteData topicRouteData = this.topicRouteTable.get(topic);
        if (topicRouteData != null) {
            List<BrokerData> brokers = topicRouteData.getBrokerDatas();
            if (!brokers.isEmpty()) {
                int index = random.nextInt(brokers.size());
                BrokerData bd = brokers.get(index % brokers.size());
                return bd.selectBrokerAddr();
            }
        }

        return null;
    }

    public synchronized void resetOffset(String topic, String group, Map<MessageQueue, Long> offsetTable) {
        DefaultMQPushConsumerImpl consumer = null;
        try {
            MQConsumerInner impl = this.consumerTable.get(group);
            if (impl instanceof DefaultMQPushConsumerImpl) {
                consumer = (DefaultMQPushConsumerImpl) impl;
            } else {
                log.info("[reset-offset] consumer dose not exist. group={}", group);
                return;
            }
            consumer.suspend();

            ConcurrentMap<MessageQueue, ProcessQueue> processQueueTable = consumer.getRebalanceImpl().getProcessQueueTable();
            for (Map.Entry<MessageQueue, ProcessQueue> entry : processQueueTable.entrySet()) {
                MessageQueue mq = entry.getKey();
                if (topic.equals(mq.getTopic()) && offsetTable.containsKey(mq)) {
                    ProcessQueue pq = entry.getValue();
                    pq.setDropped(true);
                    pq.clear();
                }
            }

            try {
                TimeUnit.SECONDS.sleep(10);
            } catch (InterruptedException ignored) {
            }

            Iterator<MessageQueue> iterator = processQueueTable.keySet().iterator();
            while (iterator.hasNext()) {
                MessageQueue mq = iterator.next();
                Long offset = offsetTable.get(mq);
                if (topic.equals(mq.getTopic()) && offset != null) {
                    try {
                        consumer.updateConsumeOffset(mq, offset);
                        consumer.getRebalanceImpl().removeUnnecessaryMessageQueue(mq, processQueueTable.get(mq));
                        iterator.remove();
                    } catch (Exception e) {
                        log.warn("reset offset failed. group={}, {}", group, mq, e);
                    }
                }
            }
        } finally {
            if (consumer != null) {
                consumer.resume();
            }
        }
    }

    @SuppressWarnings("unchecked")
    public Map<MessageQueue, Long> getConsumerStatus(String topic, String group) {
        MQConsumerInner impl = this.consumerTable.get(group);
        if (impl instanceof DefaultMQPushConsumerImpl) {
            DefaultMQPushConsumerImpl consumer = (DefaultMQPushConsumerImpl) impl;
            return consumer.getOffsetStore().cloneOffsetTable(topic);
        } else if (impl instanceof DefaultMQPullConsumerImpl) {
            DefaultMQPullConsumerImpl consumer = (DefaultMQPullConsumerImpl) impl;
            return consumer.getOffsetStore().cloneOffsetTable(topic);
        } else {
            return Collections.EMPTY_MAP;
        }
    }

    public TopicRouteData getAnExistTopicRouteData(final String topic) {
        return this.topicRouteTable.get(topic);
    }

    public MQClientAPIImpl getMQClientAPIImpl() {
        return mQClientAPIImpl;
    }

    public MQAdminImpl getMQAdminImpl() {
        return mQAdminImpl;
    }

    public long getBootTimestamp() {
        return bootTimestamp;
    }

    public ScheduledExecutorService getScheduledExecutorService() {
        return scheduledExecutorService;
    }

    public PullMessageService getPullMessageService() {
        return pullMessageService;
    }

    public DefaultMQProducer getDefaultMQProducer() {
        return defaultMQProducer;
    }

    public ConcurrentMap<String, TopicRouteData> getTopicRouteTable() {
        return topicRouteTable;
    }

    public ConsumeMessageDirectlyResult consumeMessageDirectly(final MessageExt msg,
        final String consumerGroup,
        final String brokerName) {
        MQConsumerInner mqConsumerInner = this.consumerTable.get(consumerGroup);
        if (mqConsumerInner instanceof DefaultMQPushConsumerImpl) {
            DefaultMQPushConsumerImpl consumer = (DefaultMQPushConsumerImpl) mqConsumerInner;

            return consumer.getConsumeMessageService().consumeMessageDirectly(msg, brokerName);
        }

        return null;
    }

    public ConsumerRunningInfo consumerRunningInfo(final String consumerGroup) {
        MQConsumerInner mqConsumerInner = this.consumerTable.get(consumerGroup);
        if (mqConsumerInner == null) {
            return null;
        }

        ConsumerRunningInfo consumerRunningInfo = mqConsumerInner.consumerRunningInfo();

        List<String> nsList = this.mQClientAPIImpl.getRemotingClient().getNameServerAddressList();

        StringBuilder strBuilder = new StringBuilder();
        if (nsList != null) {
            for (String addr : nsList) {
                strBuilder.append(addr).append(";");
            }
        }

        String nsAddr = strBuilder.toString();
        consumerRunningInfo.getProperties().put(ConsumerRunningInfo.PROP_NAMESERVER_ADDR, nsAddr);
        consumerRunningInfo.getProperties().put(ConsumerRunningInfo.PROP_CONSUME_TYPE, mqConsumerInner.consumeType().name());
        consumerRunningInfo.getProperties().put(ConsumerRunningInfo.PROP_CLIENT_VERSION,
            MQVersion.getVersionDesc(MQVersion.CURRENT_VERSION));

        return consumerRunningInfo;
    }

    public ConsumerStatsManager getConsumerStatsManager() {
        return consumerStatsManager;
    }

    public NettyClientConfig getNettyClientConfig() {
        return nettyClientConfig;
    }

    public ClientConfig getClientConfig() {
        return clientConfig;
    }

    public TopicRouteData queryTopicRouteData(String topic) {
        TopicRouteData data = this.getAnExistTopicRouteData(topic);
        if (data == null) {
            this.updateTopicRouteInfoFromNameServer(topic);
            data = this.getAnExistTopicRouteData(topic);
        }
        return data;
    }
}
