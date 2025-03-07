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
package org.apache.rocketmq.client.consumer;

import java.util.Collection;
import java.util.List;
import org.apache.rocketmq.client.ClientConfig;
import org.apache.rocketmq.client.consumer.rebalance.AllocateMessageQueueAveragely;
import org.apache.rocketmq.client.consumer.store.OffsetStore;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.impl.consumer.DefaultLitePullConsumerImpl;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.UtilAll;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.common.protocol.NamespaceUtil;
import org.apache.rocketmq.common.protocol.heartbeat.MessageModel;
import org.apache.rocketmq.remoting.RPCHook;

/**
 * 1. 推模式与拉模式的区别
 *  a. 对比上面推模式进行消费的例子，从使用方式上来讲，推模式不需要消费者主动去拉取消息，只需要注册消息监听器，当有消息到达时，触发consumeMessage方法进行消息消费
 *      ，从表面上看就像是Broker主动推送给消费者一样，所以叫做推模式，尽管底层还是需要消费者发起拉取请求向Broker拉取消息。
 *  b. 拉模式在使用方式上，需要消费者主动调用poll方法获取消息，从表面上看消费者需要不断主动进行消息拉取，所以叫做拉模式。
 *
 * 1. 使用DefaultMQPullConsumer 应考虑以下两个问题：（https://blog.csdn.net/qq_25145759/article/details/114299339）
 *  1.消息队列的负载均衡
 *  2. 消费点位及存储；
 * 2.DefaultLitePullConsumer 实现提供以下特性：
 *  1. 集群方式消费消息支持消息对列负载均衡；
 *  2. 广播方式消费消息支持收到广播消息对列，此时不支持负载均衡；
 *  3. 提供了seek 方法方便用户重置消费点位；
 *  4. 提供commitSync 方法方便用户手动提交消费点位。
 *
 */
public class DefaultLitePullConsumer extends ClientConfig implements LitePullConsumer {

    /**
     * 拉模式下默认的消息拉取实现类
     */
    private final DefaultLitePullConsumerImpl defaultLitePullConsumerImpl;

    /**
     * Consumers belonging to the same consumer group share a group id. The consumers in a group then divides the topic
     * as fairly amongst themselves as possible by establishing that each queue is only consumed by a single consumer
     * from the group. If all consumers are from the same group, it functions as a traditional message queue. Each
     * message would be consumed by one consumer of the group only. When multiple consumer groups exist, the flow of the
     * data consumption model aligns with the traditional publish-subscribe model. The messages are broadcast to all
     * consumer groups.
     */
    private String consumerGroup;

    /**
     * Long polling mode, the Consumer connection max suspend time, it is not recommended to modify
     */
    private long brokerSuspendMaxTimeMillis = 1000 * 20;

    /**
     * Long polling mode, the Consumer connection timeout(must greater than brokerSuspendMaxTimeMillis), it is not
     * recommended to modify
     */
    private long consumerTimeoutMillisWhenSuspend = 1000 * 30;

    /**
     * The socket timeout in milliseconds
     */
    private long consumerPullTimeoutMillis = 1000 * 10;

    /**
     * Consumption pattern,default is clustering
     */
    private MessageModel messageModel = MessageModel.CLUSTERING;
    /**
     * Message queue listener
     */
    private MessageQueueListener messageQueueListener;
    /**
     * Offset Storage
     */
    private OffsetStore offsetStore;

    /**
     * Queue allocation algorithm
     */
    private AllocateMessageQueueStrategy allocateMessageQueueStrategy = new AllocateMessageQueueAveragely();
    /**
     * Whether the unit of subscription group
     */
    private boolean unitMode = false;

    /**
     * The flag for auto commit offset
     */
    private boolean autoCommit = true;

    /**
     * Pull thread number
     */
    private int pullThreadNums = 20;

    /**
     * Minimum commit offset interval time in milliseconds.
     */
    private static final long MIN_AUTOCOMMIT_INTERVAL_MILLIS = 1000;

    /**
     * Maximum commit offset interval time in milliseconds.
     */
    private long autoCommitIntervalMillis = 5 * 1000;

    /**
     * Maximum number of messages pulled each time.
     */
    private int pullBatchSize = 10;

    /**
     * Flow control threshold for consume request, each consumer will cache at most 10000 consume requests by default.
     * Consider the {@code pullBatchSize}, the instantaneous value may exceed the limit
     */
    private long pullThresholdForAll = 10000;

    /**
     * Consume max span offset.
     * 消耗最大跨度偏移量。
     */
    private int consumeMaxSpan = 2000;

    /**
     * Flow control threshold on queue level, each message queue will cache at most 1000 messages by default, Consider
     * 队列级别上的流量控制阈值，默认情况下每个消息队列最多缓存1000条消息，请考虑
     * the {@code pullBatchSize}, the instantaneous value may exceed the limit
     */
    private int pullThresholdForQueue = 1000;

    /**
     * Limit the cached message size on queue level, each message queue will cache at most 100 MiB messages by default,
     * 在队列级别限制缓存的消息大小，默认情况下每个消息队列最多缓存100 MiB消息。
     * Consider the {@code pullBatchSize}, the instantaneous value may exceed the limit
     *
     * <p>
     * The size of a message only measured by message body, so it's not accurate
     */
    private int pullThresholdSizeForQueue = 100;

    /**
     * The poll timeout in milliseconds
     */
    private long pollTimeoutMillis = 1000 * 5;

    /**
     * Interval time in in milliseconds for checking changes in topic metadata.
     */
    private long topicMetadataCheckIntervalMillis = 30 * 1000;

    private ConsumeFromWhere consumeFromWhere = ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET;

    /**
     * Backtracking consumption time with second precision. Time format is 20131223171201<br> Implying Seventeen twelve
     * and 01 seconds on December 23, 2013 year<br> Default backtracking consumption time Half an hour ago.
     */
    private String consumeTimestamp = UtilAll.timeMillisToHumanString3(System.currentTimeMillis() - (1000 * 60 * 30));

    /**
     * Default constructor.
     */
    public DefaultLitePullConsumer() {
        this(null, MixAll.DEFAULT_CONSUMER_GROUP, null);
    }

    /**
     * Constructor specifying consumer group.
     *
     * @param consumerGroup Consumer group.
     */
    public DefaultLitePullConsumer(final String consumerGroup) {
        this(null, consumerGroup, null);
    }

    /**
     * Constructor specifying RPC hook.
     *
     * @param rpcHook RPC hook to execute before each remoting command.
     */
    public DefaultLitePullConsumer(RPCHook rpcHook) {
        this(null, MixAll.DEFAULT_CONSUMER_GROUP, rpcHook);
    }

    /**
     * Constructor specifying consumer group, RPC hook
     *
     * @param consumerGroup Consumer group.
     * @param rpcHook RPC hook to execute before each remoting command.
     */
    public DefaultLitePullConsumer(final String consumerGroup, RPCHook rpcHook) {
        this(null, consumerGroup, rpcHook);
    }

    /**
     * Constructor specifying namespace, consumer group and RPC hook.
     *
     * @param consumerGroup Consumer group.
     * @param rpcHook RPC hook to execute before each remoting command.
     */
    public DefaultLitePullConsumer(final String namespace, final String consumerGroup, RPCHook rpcHook) {
        this.namespace = namespace;
        this.consumerGroup = consumerGroup;
        // 创建DefaultLitePullConsumerImpl
        defaultLitePullConsumerImpl = new DefaultLitePullConsumerImpl(this, rpcHook);
    }

    @Override
    public void start() throws MQClientException {
        setConsumerGroup(NamespaceUtil.wrapNamespace(this.getNamespace(), this.consumerGroup));
        // 启动DefaultLitePullConsumerImpl
        this.defaultLitePullConsumerImpl.start();
    }

    @Override
    public void shutdown() {
        this.defaultLitePullConsumerImpl.shutdown();
    }

    @Override
    public boolean isRunning() {
        return this.defaultLitePullConsumerImpl.isRunning();
    }

    @Override
    public void subscribe(String topic, String subExpression) throws MQClientException {
        this.defaultLitePullConsumerImpl.subscribe(withNamespace(topic), subExpression);
    }

    @Override
    public void subscribe(String topic, MessageSelector messageSelector) throws MQClientException {
        this.defaultLitePullConsumerImpl.subscribe(withNamespace(topic), messageSelector);
    }

    @Override
    public void unsubscribe(String topic) {
        this.defaultLitePullConsumerImpl.unsubscribe(withNamespace(topic));
    }

    @Override
    public void assign(Collection<MessageQueue> messageQueues) {
        defaultLitePullConsumerImpl.assign(queuesWithNamespace(messageQueues));
    }

    @Override
    public List<MessageExt> poll() {
        return defaultLitePullConsumerImpl.poll(this.getPollTimeoutMillis());
    }

    @Override
    public List<MessageExt> poll(long timeout) {
        return defaultLitePullConsumerImpl.poll(timeout);
    }

    @Override
    public void seek(MessageQueue messageQueue, long offset) throws MQClientException {
        this.defaultLitePullConsumerImpl.seek(queueWithNamespace(messageQueue), offset);
    }

    @Override
    public void pause(Collection<MessageQueue> messageQueues) {
        this.defaultLitePullConsumerImpl.pause(queuesWithNamespace(messageQueues));
    }

    @Override
    public void resume(Collection<MessageQueue> messageQueues) {
        this.defaultLitePullConsumerImpl.resume(queuesWithNamespace(messageQueues));
    }

    @Override
    public Collection<MessageQueue> fetchMessageQueues(String topic) throws MQClientException {
        return this.defaultLitePullConsumerImpl.fetchMessageQueues(withNamespace(topic));
    }

    @Override
    public Long offsetForTimestamp(MessageQueue messageQueue, Long timestamp) throws MQClientException {
        return this.defaultLitePullConsumerImpl.searchOffset(queueWithNamespace(messageQueue), timestamp);
    }

    @Override
    public void registerTopicMessageQueueChangeListener(String topic,
        TopicMessageQueueChangeListener topicMessageQueueChangeListener) throws MQClientException {
        this.defaultLitePullConsumerImpl.registerTopicMessageQueueChangeListener(withNamespace(topic), topicMessageQueueChangeListener);
    }

    @Override
    public void commitSync() {
        this.defaultLitePullConsumerImpl.commitAll();
    }

    @Override
    public Long committed(MessageQueue messageQueue) throws MQClientException {
        return this.defaultLitePullConsumerImpl.committed(queueWithNamespace(messageQueue));
    }

    @Override
    public void updateNameServerAddress(String nameServerAddress) {
        this.defaultLitePullConsumerImpl.updateNameServerAddr(nameServerAddress);
    }

    @Override
    public void seekToBegin(MessageQueue messageQueue) throws MQClientException {
        this.defaultLitePullConsumerImpl.seekToBegin(queueWithNamespace(messageQueue));
    }

    @Override
    public void seekToEnd(MessageQueue messageQueue) throws MQClientException {
        this.defaultLitePullConsumerImpl.seekToEnd(queueWithNamespace(messageQueue));
    }

    @Override
    public boolean isAutoCommit() {
        return autoCommit;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) {
        this.autoCommit = autoCommit;
    }

    public boolean isConnectBrokerByUser() {
        return this.defaultLitePullConsumerImpl.getPullAPIWrapper().isConnectBrokerByUser();
    }

    public void setConnectBrokerByUser(boolean connectBrokerByUser) {
        this.defaultLitePullConsumerImpl.getPullAPIWrapper().setConnectBrokerByUser(connectBrokerByUser);
    }

    public long getDefaultBrokerId() {
        return this.defaultLitePullConsumerImpl.getPullAPIWrapper().getDefaultBrokerId();
    }

    public void setDefaultBrokerId(long defaultBrokerId) {
        this.defaultLitePullConsumerImpl.getPullAPIWrapper().setDefaultBrokerId(defaultBrokerId);
    }

    public int getPullThreadNums() {
        return pullThreadNums;
    }

    public void setPullThreadNums(int pullThreadNums) {
        this.pullThreadNums = pullThreadNums;
    }

    public long getAutoCommitIntervalMillis() {
        return autoCommitIntervalMillis;
    }

    public void setAutoCommitIntervalMillis(long autoCommitIntervalMillis) {
        if (autoCommitIntervalMillis >= MIN_AUTOCOMMIT_INTERVAL_MILLIS) {
            this.autoCommitIntervalMillis = autoCommitIntervalMillis;
        }
    }

    public int getPullBatchSize() {
        return pullBatchSize;
    }

    public void setPullBatchSize(int pullBatchSize) {
        this.pullBatchSize = pullBatchSize;
    }

    public long getPullThresholdForAll() {
        return pullThresholdForAll;
    }

    public void setPullThresholdForAll(long pullThresholdForAll) {
        this.pullThresholdForAll = pullThresholdForAll;
    }

    public int getConsumeMaxSpan() {
        return consumeMaxSpan;
    }

    public void setConsumeMaxSpan(int consumeMaxSpan) {
        this.consumeMaxSpan = consumeMaxSpan;
    }

    public int getPullThresholdForQueue() {
        return pullThresholdForQueue;
    }

    public void setPullThresholdForQueue(int pullThresholdForQueue) {
        this.pullThresholdForQueue = pullThresholdForQueue;
    }

    public int getPullThresholdSizeForQueue() {
        return pullThresholdSizeForQueue;
    }

    public void setPullThresholdSizeForQueue(int pullThresholdSizeForQueue) {
        this.pullThresholdSizeForQueue = pullThresholdSizeForQueue;
    }

    public AllocateMessageQueueStrategy getAllocateMessageQueueStrategy() {
        return allocateMessageQueueStrategy;
    }

    public void setAllocateMessageQueueStrategy(AllocateMessageQueueStrategy allocateMessageQueueStrategy) {
        this.allocateMessageQueueStrategy = allocateMessageQueueStrategy;
    }

    public long getBrokerSuspendMaxTimeMillis() {
        return brokerSuspendMaxTimeMillis;
    }

    public long getPollTimeoutMillis() {
        return pollTimeoutMillis;
    }

    public void setPollTimeoutMillis(long pollTimeoutMillis) {
        this.pollTimeoutMillis = pollTimeoutMillis;
    }

    public OffsetStore getOffsetStore() {
        return offsetStore;
    }

    public void setOffsetStore(OffsetStore offsetStore) {
        this.offsetStore = offsetStore;
    }

    public boolean isUnitMode() {
        return unitMode;
    }

    public void setUnitMode(boolean isUnitMode) {
        this.unitMode = isUnitMode;
    }

    public MessageModel getMessageModel() {
        return messageModel;
    }

    public void setMessageModel(MessageModel messageModel) {
        this.messageModel = messageModel;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public MessageQueueListener getMessageQueueListener() {
        return messageQueueListener;
    }

    public void setMessageQueueListener(MessageQueueListener messageQueueListener) {
        this.messageQueueListener = messageQueueListener;
    }

    public long getConsumerPullTimeoutMillis() {
        return consumerPullTimeoutMillis;
    }

    public void setConsumerPullTimeoutMillis(long consumerPullTimeoutMillis) {
        this.consumerPullTimeoutMillis = consumerPullTimeoutMillis;
    }

    public long getConsumerTimeoutMillisWhenSuspend() {
        return consumerTimeoutMillisWhenSuspend;
    }

    public void setConsumerTimeoutMillisWhenSuspend(long consumerTimeoutMillisWhenSuspend) {
        this.consumerTimeoutMillisWhenSuspend = consumerTimeoutMillisWhenSuspend;
    }

    public long getTopicMetadataCheckIntervalMillis() {
        return topicMetadataCheckIntervalMillis;
    }

    public void setTopicMetadataCheckIntervalMillis(long topicMetadataCheckIntervalMillis) {
        this.topicMetadataCheckIntervalMillis = topicMetadataCheckIntervalMillis;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public ConsumeFromWhere getConsumeFromWhere() {
        return consumeFromWhere;
    }

    public void setConsumeFromWhere(ConsumeFromWhere consumeFromWhere) {
        if (consumeFromWhere != ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET
            && consumeFromWhere != ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET
            && consumeFromWhere != ConsumeFromWhere.CONSUME_FROM_TIMESTAMP) {
            throw new RuntimeException("Invalid ConsumeFromWhere Value", null);
        }
        this.consumeFromWhere = consumeFromWhere;
    }

    public String getConsumeTimestamp() {
        return consumeTimestamp;
    }

    public void setConsumeTimestamp(String consumeTimestamp) {
        this.consumeTimestamp = consumeTimestamp;
    }
}
