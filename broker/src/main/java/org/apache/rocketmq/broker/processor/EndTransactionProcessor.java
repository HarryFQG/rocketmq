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
package org.apache.rocketmq.broker.processor;

import io.netty.channel.ChannelHandlerContext;
import org.apache.rocketmq.broker.BrokerController;
import org.apache.rocketmq.broker.transaction.OperationResult;
import org.apache.rocketmq.common.TopicFilterType;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.message.MessageAccessor;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageDecoder;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.protocol.ResponseCode;
import org.apache.rocketmq.common.protocol.header.EndTransactionRequestHeader;
import org.apache.rocketmq.common.sysflag.MessageSysFlag;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.remoting.common.RemotingHelper;
import org.apache.rocketmq.remoting.exception.RemotingCommandException;
import org.apache.rocketmq.remoting.netty.AsyncNettyRequestProcessor;
import org.apache.rocketmq.remoting.netty.NettyRequestProcessor;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.store.MessageExtBrokerInner;
import org.apache.rocketmq.store.PutMessageResult;
import org.apache.rocketmq.store.config.BrokerRole;

/**
 * EndTransaction processor: process commit and rollback message
 */
public class EndTransactionProcessor extends AsyncNettyRequestProcessor implements NettyRequestProcessor {
    private static final InternalLogger LOGGER = InternalLoggerFactory.getLogger(LoggerName.TRANSACTION_LOGGER_NAME);
    private final BrokerController brokerController;

    public EndTransactionProcessor(final BrokerController brokerController) {
        this.brokerController = brokerController;
    }

    /**
     * {@link org.apache.rocketmq.client.impl.producer.DefaultMQProducerImpl#endTransaction} 处理这个请求
     *Broker对事务结束的请求处理在EndTransactionProcessor中：
     *  1. 判断是否是从节点，从节点没有结束事务的权限，如果是从节点返回SLAVE_NOT_AVAILABLE
     *  2. 从请求头中获取事务的提交类型：
     *      a. TRANSACTION_COMMIT_TYPE：表示提交事务，会调用commitMessage方法提交消息，如果提交成功调用endMessageTransaction结束事务，恢复消息的原始主题和队列并调用deletePrepareMessage方法删掉half消息
     *      b. TRANSACTION_ROLLBACK_TYPE：表示回滚事务，会调用rollbackMessage方法回滚事务，然后删掉half消息
     * @param ctx
     * @param request
     * @return
     * @throws RemotingCommandException
     */
    @Override
    public RemotingCommand processRequest(ChannelHandlerContext ctx, RemotingCommand request) throws
        RemotingCommandException {
        // 创建响应
        final RemotingCommand response = RemotingCommand.createResponseCommand(null);
        final EndTransactionRequestHeader requestHeader =
            (EndTransactionRequestHeader)request.decodeCommandCustomHeader(EndTransactionRequestHeader.class);
        LOGGER.debug("Transaction request:{}", requestHeader);
        // 如果是从节点，从节点没有结束事务的权限，返回SLAVE_NOT_AVAILABLE
        if (BrokerRole.SLAVE == brokerController.getMessageStoreConfig().getBrokerRole()) {
            response.setCode(ResponseCode.SLAVE_NOT_AVAILABLE);
            LOGGER.warn("Message store is slave mode, so end transaction is forbidden. ");
            return response;
        }

        if (requestHeader.getFromTransactionCheck()) {
            switch (requestHeader.getCommitOrRollback()) {
                case MessageSysFlag.TRANSACTION_NOT_TYPE: {
                    LOGGER.warn("Check producer[{}] transaction state, but it's pending status."
                            + "RequestHeader: {} Remark: {}",
                        RemotingHelper.parseChannelRemoteAddr(ctx.channel()),
                        requestHeader.toString(),
                        request.getRemark());
                    return null;
                }

                case MessageSysFlag.TRANSACTION_COMMIT_TYPE: {
                    LOGGER.warn("Check producer[{}] transaction state, the producer commit the message."
                            + "RequestHeader: {} Remark: {}",
                        RemotingHelper.parseChannelRemoteAddr(ctx.channel()),
                        requestHeader.toString(),
                        request.getRemark());

                    break;
                }

                case MessageSysFlag.TRANSACTION_ROLLBACK_TYPE: {
                    LOGGER.warn("Check producer[{}] transaction state, the producer rollback the message."
                            + "RequestHeader: {} Remark: {}",
                        RemotingHelper.parseChannelRemoteAddr(ctx.channel()),
                        requestHeader.toString(),
                        request.getRemark());
                    break;
                }
                default:
                    return null;
            }
        } else {
            switch (requestHeader.getCommitOrRollback()) {
                case MessageSysFlag.TRANSACTION_NOT_TYPE: {
                    LOGGER.warn("The producer[{}] end transaction in sending message,  and it's pending status."
                            + "RequestHeader: {} Remark: {}",
                        RemotingHelper.parseChannelRemoteAddr(ctx.channel()),
                        requestHeader.toString(),
                        request.getRemark());
                    return null;
                }

                case MessageSysFlag.TRANSACTION_COMMIT_TYPE: {
                    break;
                }

                case MessageSysFlag.TRANSACTION_ROLLBACK_TYPE: {
                    LOGGER.warn("The producer[{}] end transaction in sending message, rollback the message."
                            + "RequestHeader: {} Remark: {}",
                        RemotingHelper.parseChannelRemoteAddr(ctx.channel()),
                        requestHeader.toString(),
                        request.getRemark());
                    break;
                }
                default:
                    return null;
            }
        }
        OperationResult result = new OperationResult();
        // 判断事务提交类型，如果是提交事务 , 如果是事物提交了
        if (MessageSysFlag.TRANSACTION_COMMIT_TYPE == requestHeader.getCommitOrRollback()) {
            //提交消息 根据消息的偏移量从commitLog中获取消息
            result = this.brokerController.getTransactionalMessageService().commitMessage(requestHeader);
            if (result.getResponseCode() == ResponseCode.SUCCESS) {
                // 校验Prepare消息
                RemotingCommand res = checkPrepareMessage(result.getPrepareMessage(), requestHeader);
                if (res.getCode() == ResponseCode.SUCCESS) {
                    // 结束事务，恢复消息的原始主题和队列
                    MessageExtBrokerInner msgInner = endMessageTransaction(result.getPrepareMessage());
                    msgInner.setSysFlag(MessageSysFlag.resetTransactionValue(msgInner.getSysFlag(), requestHeader.getCommitOrRollback()));
                    msgInner.setQueueOffset(requestHeader.getTranStateTableOffset());
                    // 真正投递的topic的offset 与 half的offset 相等
                    msgInner.setPreparedTransactionOffset(requestHeader.getCommitLogOffset());
                    msgInner.setStoreTimestamp(result.getPrepareMessage().getStoreTimestamp());
                    MessageAccessor.clearProperty(msgInner, MessageConst.PROPERTY_TRANSACTION_PREPARED);
                    // 同步存储消息
                    RemotingCommand sendResult = sendFinalMessage(msgInner);
                    // 如果存储(store)层返回success
                    if (sendResult.getCode() == ResponseCode.SUCCESS) {
                        //删除half消息, 不会直接删除消息
                        // 由于CommitLog追加写的性质，RocketMQ并不会直接将half消息从CommitLog中删除，而是使用了另外一个
                        // OP主题RMQ_SYS_TRANS_OP_HALF_TOPIC（以下简称OP主题/队列），将已经提交/回滚的消息记录在OP主题队列中
                        this.brokerController.getTransactionalMessageService().deletePrepareMessage(result.getPrepareMessage());
                    }
                    return sendResult;
                }
                return res;
            }
        } else if (MessageSysFlag.TRANSACTION_ROLLBACK_TYPE == requestHeader.getCommitOrRollback()) {  // 如果是回滚
            // 回滚消息 根据消息的偏移量从commitLog中获取消息
            result = this.brokerController.getTransactionalMessageService().rollbackMessage(requestHeader);
            if (result.getResponseCode() == ResponseCode.SUCCESS) {
                RemotingCommand res = checkPrepareMessage(result.getPrepareMessage(), requestHeader);
                if (res.getCode() == ResponseCode.SUCCESS) {
                    // 删除half消息,其实就是添加到Op 消息队列中
                    this.brokerController.getTransactionalMessageService().deletePrepareMessage(result.getPrepareMessage());
                }
                return res;
            }
        }
        response.setCode(result.getResponseCode());
        response.setRemark(result.getResponseRemark());
        return response;
    }

    @Override
    public boolean rejectRequest() {
        return false;
    }

    private RemotingCommand checkPrepareMessage(MessageExt msgExt, EndTransactionRequestHeader requestHeader) {
        final RemotingCommand response = RemotingCommand.createResponseCommand(null);
        if (msgExt != null) {
            final String pgroupRead = msgExt.getProperty(MessageConst.PROPERTY_PRODUCER_GROUP);
            if (!pgroupRead.equals(requestHeader.getProducerGroup())) {
                response.setCode(ResponseCode.SYSTEM_ERROR);
                response.setRemark("The producer group wrong");
                return response;
            }

            // 这个是查询来的消息的offset与传进来的offset 不想等
            if (msgExt.getQueueOffset() != requestHeader.getTranStateTableOffset()) {
                response.setCode(ResponseCode.SYSTEM_ERROR);
                response.setRemark("The transaction state table offset wrong");
                return response;
            }

            if (msgExt.getCommitLogOffset() != requestHeader.getCommitLogOffset()) {
                response.setCode(ResponseCode.SYSTEM_ERROR);
                response.setRemark("The commit log offset wrong");
                return response;
            }
        } else {
            response.setCode(ResponseCode.SYSTEM_ERROR);
            response.setRemark("Find prepared transaction message failed");
            return response;
        }
        response.setCode(ResponseCode.SUCCESS);
        return response;
    }

    /**
     * 根据half中的存储的真实的topic，queueId信息，放入到对应topic中，并根据tag构建 filter
     * @param msgExt
     * @return
     */
    private MessageExtBrokerInner endMessageTransaction(MessageExt msgExt) {
        MessageExtBrokerInner msgInner = new MessageExtBrokerInner();
        msgInner.setTopic(msgExt.getUserProperty(MessageConst.PROPERTY_REAL_TOPIC));
        msgInner.setQueueId(Integer.parseInt(msgExt.getUserProperty(MessageConst.PROPERTY_REAL_QUEUE_ID)));
        msgInner.setBody(msgExt.getBody());
        msgInner.setFlag(msgExt.getFlag());
        msgInner.setBornTimestamp(msgExt.getBornTimestamp());
        msgInner.setBornHost(msgExt.getBornHost());
        msgInner.setStoreHost(msgExt.getStoreHost());
        msgInner.setReconsumeTimes(msgExt.getReconsumeTimes());
        msgInner.setWaitStoreMsgOK(false);
        msgInner.setTransactionId(msgExt.getUserProperty(MessageConst.PROPERTY_UNIQ_CLIENT_MESSAGE_ID_KEYIDX));
        msgInner.setSysFlag(msgExt.getSysFlag());
        // 根据tag构建filter
        TopicFilterType topicFilterType =
            (msgInner.getSysFlag() & MessageSysFlag.MULTI_TAGS_FLAG) == MessageSysFlag.MULTI_TAGS_FLAG ? TopicFilterType.MULTI_TAG
                : TopicFilterType.SINGLE_TAG;
        long tagsCodeValue = MessageExtBrokerInner.tagsString2tagsCode(topicFilterType, msgInner.getTags());
        msgInner.setTagsCode(tagsCodeValue);
        MessageAccessor.setProperties(msgInner, msgExt.getProperties());
        msgInner.setPropertiesString(MessageDecoder.messageProperties2String(msgExt.getProperties()));
        MessageAccessor.clearProperty(msgInner, MessageConst.PROPERTY_REAL_TOPIC);
        MessageAccessor.clearProperty(msgInner, MessageConst.PROPERTY_REAL_QUEUE_ID);
        return msgInner;
    }

    private RemotingCommand sendFinalMessage(MessageExtBrokerInner msgInner) {
        final RemotingCommand response = RemotingCommand.createResponseCommand(null);
        // 同步存储消息
        final PutMessageResult putMessageResult = this.brokerController.getMessageStore().putMessage(msgInner);
        if (putMessageResult != null) {
            switch (putMessageResult.getPutMessageStatus()) {
                // Success
                case PUT_OK:
                case FLUSH_DISK_TIMEOUT:
                case FLUSH_SLAVE_TIMEOUT:
                case SLAVE_NOT_AVAILABLE:
                    response.setCode(ResponseCode.SUCCESS);
                    response.setRemark(null);
                    break;
                // Failed
                case CREATE_MAPEDFILE_FAILED:
                    response.setCode(ResponseCode.SYSTEM_ERROR);
                    response.setRemark("Create mapped file failed.");
                    break;
                case MESSAGE_ILLEGAL:
                case PROPERTIES_SIZE_EXCEEDED:
                    response.setCode(ResponseCode.MESSAGE_ILLEGAL);
                    response.setRemark("The message is illegal, maybe msg body or properties length not matched. msg body length limit 128k, msg properties length limit 32k.");
                    break;
                case SERVICE_NOT_AVAILABLE:
                    response.setCode(ResponseCode.SERVICE_NOT_AVAILABLE);
                    response.setRemark("Service not available now.");
                    break;
                case OS_PAGECACHE_BUSY:
                    response.setCode(ResponseCode.SYSTEM_ERROR);
                    response.setRemark("OS page cache busy, please try another machine");
                    break;
                case UNKNOWN_ERROR:
                    response.setCode(ResponseCode.SYSTEM_ERROR);
                    response.setRemark("UNKNOWN_ERROR");
                    break;
                default:
                    response.setCode(ResponseCode.SYSTEM_ERROR);
                    response.setRemark("UNKNOWN_ERROR DEFAULT");
                    break;
            }
            return response;
        } else {
            response.setCode(ResponseCode.SYSTEM_ERROR);
            response.setRemark("store putMessage return null");
        }
        return response;
    }
}
