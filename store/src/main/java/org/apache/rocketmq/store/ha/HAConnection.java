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
package org.apache.rocketmq.store.ha;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import org.apache.rocketmq.common.ServiceThread;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.remoting.common.RemotingUtil;
import org.apache.rocketmq.store.SelectMappedBufferResult;

/**
 * HAConnection中封装了Master节点与从节点的网络通信处理，分别在ReadSocketService和WriteSocketService中
 */
public class HAConnection {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);
    private final HAService haService;
    private final SocketChannel socketChannel;
    private final String clientAddr;
    private WriteSocketService writeSocketService;
    private ReadSocketService readSocketService;

    private volatile long slaveRequestOffset = -1;
    private volatile long slaveAckOffset = -1;

    public HAConnection(final HAService haService, final SocketChannel socketChannel) throws IOException {
        this.haService = haService;
        this.socketChannel = socketChannel;
        this.clientAddr = this.socketChannel.socket().getRemoteSocketAddress().toString();
        this.socketChannel.configureBlocking(false);
        this.socketChannel.socket().setSoLinger(false, -1);
        this.socketChannel.socket().setTcpNoDelay(true);
        this.socketChannel.socket().setReceiveBufferSize(1024 * 64);
        this.socketChannel.socket().setSendBufferSize(1024 * 64);
        this.writeSocketService = new WriteSocketService(this.socketChannel);
        this.readSocketService = new ReadSocketService(this.socketChannel);
        this.haService.getConnectionCount().incrementAndGet();
    }

    public void start() {
        this.readSocketService.start();
        this.writeSocketService.start();
    }

    public void shutdown() {
        this.writeSocketService.shutdown(true);
        this.readSocketService.shutdown(true);
        this.close();
    }

    public void close() {
        if (this.socketChannel != null) {
            try {
                this.socketChannel.close();
            } catch (IOException e) {
                HAConnection.log.error("", e);
            }
        }
    }

    public SocketChannel getSocketChannel() {
        return socketChannel;
    }

    /**
     * ReadSocketService启动后处理监听到的可读事件，前面知道HAClient中从节点会定时向Master节点汇报从节点的消息同步偏移量，Master节点对汇报请求的处理就在这里
     * ，如果从网络中监听到了可读事件，会调用processReadEvent处理读事件。
     *
     */
    class ReadSocketService extends ServiceThread {
        private static final int READ_MAX_BUFFER_SIZE = 1024 * 1024;
        private final Selector selector;
        private final SocketChannel socketChannel;
        /**
         * 读缓冲区
         */
        private final ByteBuffer byteBufferRead = ByteBuffer.allocate(READ_MAX_BUFFER_SIZE);
        /**
         * 读缓冲区中已经处理的数据位置
         */
        private int processPosition = 0;
        private volatile long lastReadTimestamp = System.currentTimeMillis();

        public ReadSocketService(final SocketChannel socketChannel) throws IOException {
            this.selector = RemotingUtil.openSelector();
            this.socketChannel = socketChannel;
            this.socketChannel.register(this.selector, SelectionKey.OP_READ);
            this.setDaemon(true);
        }

        @Override
        public void run() {
            HAConnection.log.info(this.getServiceName() + " service started");

            while (!this.isStopped()) {
                try {
                    this.selector.select(1000);
                    // 处理可读事件
                    boolean ok = this.processReadEvent();
                    if (!ok) {
                        HAConnection.log.error("processReadEvent error");
                        break;
                    }

                    long interval = HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now() - this.lastReadTimestamp;
                    if (interval > HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig().getHaHousekeepingInterval()) {
                        log.warn("ha housekeeping, found this connection[" + HAConnection.this.clientAddr + "] expired, " + interval);
                        break;
                    }
                } catch (Exception e) {
                    HAConnection.log.error(this.getServiceName() + " service has exception.", e);
                    break;
                }
            }

            this.makeStop();

            writeSocketService.makeStop();

            haService.removeConnection(HAConnection.this);

            HAConnection.this.haService.getConnectionCount().decrementAndGet();

            SelectionKey sk = this.socketChannel.keyFor(this.selector);
            if (sk != null) {
                sk.cancel();
            }

            try {
                this.selector.close();
                this.socketChannel.close();
            } catch (IOException e) {
                HAConnection.log.error("", e);
            }

            HAConnection.log.info(this.getServiceName() + " service end");
        }

        @Override
        public String getServiceName() {
            return ReadSocketService.class.getSimpleName();
        }

        /**
         * 处理可读事件
         *1. processReadEvent中从网络中处理读事件的方式与上面HAClient的dispatchReadRequest类似，都是将网络中的数据读取到读缓冲区中，并用一个变量记录已读取数据的位置
         * ，processReadEvent方法的处理逻辑如下：
         *      a. 从socketChannel读取数据到读缓冲区byteBufferRead中，返回读取到的字节数；
         *      b. 如果读取到的字节数大于0，进入下一步，如果读取到的字节数为0，记录连续读取到空字节数的次数是否超过三次，如果超过终止处理；
         *      c. 判断剩余可读取的字节数是否大于等于8，前面知道，从节点发送同步消息拉取偏移量的时候设置的字节大小为8，所以字节数大于等于8的时候表示需要读取从节点发送的偏移量；
         *      d. 计算数据在缓冲区中的位置，从缓冲区读取从节点发送的同步偏移量readOffset；
         *      5. 更新processPosition的值，processPosition表示读缓冲区中已经处理数据的位置；
         *      7. 更新slaveAckOffset为从节点发送的同步偏移量readOffset的值；
         *      8. 如果当前Master节点记录的从节点的同步偏移量slaveRequestOffset小于0，表示还未进行同步，此时将slaveRequestOffset更新为从节点发送的同步偏移量；
         *      9. 如果从节点发送的同步偏移量比当前Master节点的最大物理偏移量还要大，终止本次处理；
         *      10. 调用notifyTransferSome，更新Master节点记录的向从节点同步消息的偏移量；
         *
         *
         * @return
         */
        private boolean processReadEvent() {
            int readSizeZeroTimes = 0;

            // 如果没有可读数据
            if (!this.byteBufferRead.hasRemaining()) {
                this.byteBufferRead.flip();
                // 处理位置置为0
                this.processPosition = 0;
            }

            // 如果数据未读取完毕
            while (this.byteBufferRead.hasRemaining()) {
                try {
                    // 从socketChannel读取数据到byteBufferRead中，返回读取到的字节数
                    int readSize = this.socketChannel.read(this.byteBufferRead);
                    // 如果读取数据字节数大于0
                    if (readSize > 0) {
                        // 重置readSizeZeroTimes
                        readSizeZeroTimes = 0;
                        // 获取上次处理读事件的时间戳
                        this.lastReadTimestamp = HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now();
                        // 判断剩余可读取的字节数是否大于等于8
                        if ((this.byteBufferRead.position() - this.processPosition) >= 8) {
                            // 获取偏移量内容的结束位置
                            int pos = this.byteBufferRead.position() - (this.byteBufferRead.position() % 8);
                            // 从结束位置向前读取8个字节得到从点发送的同步偏移量
                            long readOffset = this.byteBufferRead.getLong(pos - 8);
                            // 更新处理位置
                            this.processPosition = pos;

                            // 更新slaveAckOffset为从节点发送的同步进度
                            HAConnection.this.slaveAckOffset = readOffset;
                            // 如果记录的从节点的同步进度小于0，表示还未进行同步
                            if (HAConnection.this.slaveRequestOffset < 0) {
                                // 更新为从节点发送的同步进度
                                HAConnection.this.slaveRequestOffset = readOffset;
                                log.info("slave[" + HAConnection.this.clientAddr + "] request offset " + readOffset);
                            }

                            // 更新Master节点记录的向从节点同步消息的偏移量
                            HAConnection.this.haService.notifyTransferSome(HAConnection.this.slaveAckOffset);
                        }
                    } else if (readSize == 0) {
                        // 判断连续读取到空数据的次数是否超过三次
                        if (++readSizeZeroTimes >= 3) {
                            break;
                        }
                    } else {
                        log.error("read socket[" + HAConnection.this.clientAddr + "] < 0");
                        return false;
                    }
                } catch (IOException e) {
                    log.error("processReadEvent exception", e);
                    return false;
                }
            }

            return true;
        }
    }

    /**
     * 1. WriteSocketService用于Master节点向从节点发送同步消息，处理逻辑如下：
     *  a. 根据从节点发送的主从同步消息拉取偏移量slaveRequestOffset进行判断：
     *      1. 如果slaveRequestOffset值为-1，表示还未收到从节点报告的同步偏移量，此时睡眠一段时间等待从节点发送消息拉取偏移量；
     *      2. 如果slaveRequestOffset值不为-1，表示已经开始进行主从同步进行下一步；
     *  b. 判断nextTransferFromWhere值是否为-1，nextTransferFromWhere记录了下次需要传输的消息在CommitLog中的偏移量，
     *     如果值为-1表示初次进行数据同步，此时有两种情况：
     *      1. 如果从节点发送的拉取偏移量slaveRequestOffset为0，就从当前CommitLog文件最大偏移量开始同步；
     *      2. 如果slaveRequestOffset不为0，则从slaveRequestOffset位置处进行数据同步；
     *  c. 判断上次写事件是否已经将数据都写入到从节点
     *      1. 如果已经写入完毕，判断距离上次写入数据的时间间隔是否超过了设置的心跳时间，如果超过，为了避免连接空闲被关闭，需要发送一个心跳包，此时构建心跳包的请求数据，调用transferData方法传输数据；
     *      2. 如果上次的数据还未传输完毕，调用transferData方法继续传输，如果还是未完成，则结束此处处理；
     *  d. 根据nextTransferFromWhere从CommitLog中获取消息，如果未获取到消息，等待100ms，如果获取到消息，从CommitLog中获取消息进行传输：
     *      1. 如果获取到消息的字节数大于最大传输的大小，设置最最大传输数量，分批进行传输；
     *      2. 更新下次传输的偏移量地址也就是nextTransferFromWhere的值；
     *      3. 从CommitLog中获取的消息内容设置到将读取到的消息数据设置到selectMappedBufferResult中；
     *      4. 设置消息头信息，包括消息头字节数、拉取消息的偏移量等；
     *      5. 调用transferData发送数据；
     *
     *
     */
    class WriteSocketService extends ServiceThread {
        private final Selector selector;
        private final SocketChannel socketChannel;

        /**
         * 消息头大小
         */
        private final int headerSize = 8 + 4;
        private final ByteBuffer byteBufferHeader = ByteBuffer.allocate(headerSize);
        private long nextTransferFromWhere = -1;
        private SelectMappedBufferResult selectMappedBufferResult;
        private boolean lastWriteOver = true;
        private long lastWriteTimestamp = System.currentTimeMillis();

        public WriteSocketService(final SocketChannel socketChannel) throws IOException {
            this.selector = RemotingUtil.openSelector();
            this.socketChannel = socketChannel;
            this.socketChannel.register(this.selector, SelectionKey.OP_WRITE);
            this.setDaemon(true);
        }

        @Override
        public void run() {
            HAConnection.log.info(this.getServiceName() + " service started");

            while (!this.isStopped()) {
                try {
                    this.selector.select(1000);

                    // 如果slaveRequestOffset为-1，表示还未收到从节点报告的拉取进度
                    if (-1 == HAConnection.this.slaveRequestOffset) {
                        // 等待一段时间
                        Thread.sleep(10);
                        continue;
                    }

                    // 初次进行数据同步
                    if (-1 == this.nextTransferFromWhere) {
                        // 如果拉取进度为0
                        if (0 == HAConnection.this.slaveRequestOffset) {
                            // 从master节点最大偏移量开始传输
                            long masterOffset = HAConnection.this.haService.getDefaultMessageStore().getCommitLog().getMaxOffset();
                            masterOffset =
                                masterOffset
                                    - (masterOffset % HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig()
                                    .getMappedFileSizeCommitLog());

                            if (masterOffset < 0) {
                                masterOffset = 0;
                            }

                            // 更新nextTransferFromWhere
                            this.nextTransferFromWhere = masterOffset;
                        } else {
                            // 根据从节点发送的偏移量开始数据同步
                            this.nextTransferFromWhere = HAConnection.this.slaveRequestOffset;
                        }

                        log.info("master transfer data from " + this.nextTransferFromWhere + " to slave[" + HAConnection.this.clientAddr
                            + "], and slave request " + HAConnection.this.slaveRequestOffset);
                    }

                    // 判断上次传输是否完毕
                    if (this.lastWriteOver) {

                        // 获取当前时间距离上次写入数据的时间间隔
                        long interval =
                            HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now() - this.lastWriteTimestamp;

                        // 如果距离上次写入数据的时间间隔超过了设置的心跳时间
                        if (interval > HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig()
                            .getHaSendHeartbeatInterval()) {

                            // Build Header
                            // 构建header
                            this.byteBufferHeader.position(0);
                            this.byteBufferHeader.limit(headerSize);
                            this.byteBufferHeader.putLong(this.nextTransferFromWhere);
                            this.byteBufferHeader.putInt(0);
                            this.byteBufferHeader.flip();

                            // 发送心跳包
                            this.lastWriteOver = this.transferData();
                            if (!this.lastWriteOver)
                                continue;
                        }
                    } else {
                        // 未传输完毕，继续上次的传输
                        this.lastWriteOver = this.transferData();
                        // 如果依旧未完成，结束本次处理
                        if (!this.lastWriteOver)
                            continue;
                    }

                    // 根据偏移量获取消息数据
                    SelectMappedBufferResult selectResult =
                        HAConnection.this.haService.getDefaultMessageStore().getCommitLogData(this.nextTransferFromWhere);
                    // 获取消息不为空
                    if (selectResult != null) {
                        // 获取消息内容大小
                        int size = selectResult.getSize();
                        // 如果消息的字节数大于最大传输的大小
                        if (size > HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig().getHaTransferBatchSize()) {
                            // 设置为最大传输大小
                            size = HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig().getHaTransferBatchSize();
                        }

                        long thisOffset = this.nextTransferFromWhere;
                        // 更新下次传输的偏移量地址
                        this.nextTransferFromWhere += size;

                        selectResult.getByteBuffer().limit(size);
                        // 将读取到的消息数据设置到selectMappedBufferResult
                        this.selectMappedBufferResult = selectResult;

                        // 设置消息头
                        this.byteBufferHeader.position(0);
                        // 设置消息头大小
                        this.byteBufferHeader.limit(headerSize);
                        // 设置偏移量地址
                        this.byteBufferHeader.putLong(thisOffset);
                        // 设置消息内容大小
                        this.byteBufferHeader.putInt(size);
                        this.byteBufferHeader.flip();

                        // 发送数据
                        this.lastWriteOver = this.transferData();
                    } else {

                        // 等待100ms
                        HAConnection.this.haService.getWaitNotifyObject().allWaitForRunning(100);
                    }
                } catch (Exception e) {

                    HAConnection.log.error(this.getServiceName() + " service has exception.", e);
                    break;
                }
            }

            HAConnection.this.haService.getWaitNotifyObject().removeFromWaitingThreadTable();

            if (this.selectMappedBufferResult != null) {
                this.selectMappedBufferResult.release();
            }

            this.makeStop();

            readSocketService.makeStop();

            haService.removeConnection(HAConnection.this);

            SelectionKey sk = this.socketChannel.keyFor(this.selector);
            if (sk != null) {
                sk.cancel();
            }

            try {
                this.selector.close();
                this.socketChannel.close();
            } catch (IOException e) {
                HAConnection.log.error("", e);
            }

            HAConnection.log.info(this.getServiceName() + " service end");
        }

        /**
         * 发送数据
         *1. transferData方法的处理逻辑如下：
         *  a. 发送消息头数据;
         *  b. 消息头数据发送完毕之后，发送消息内容，前面知道从CommitLog中读取的消息内容放入到了selectMappedBufferResult，将selectMappedBufferResult的内容发送给从节点;
         *
         *
         * @return
         * @throws Exception
         */
        private boolean transferData() throws Exception {
            int writeSizeZeroTimes = 0;
            // Write Header
            // 写入消息头
            while (this.byteBufferHeader.hasRemaining()) {
                // 发送消息头数据
                int writeSize = this.socketChannel.write(this.byteBufferHeader);
                if (writeSize > 0) {
                    writeSizeZeroTimes = 0;
                    // 记录发送时间
                    this.lastWriteTimestamp = HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now();
                } else if (writeSize == 0) {
                    if (++writeSizeZeroTimes >= 3) {
                        break;
                    }
                } else {
                    throw new Exception("ha master write header error < 0");
                }
            }


            if (null == this.selectMappedBufferResult) {
                return !this.byteBufferHeader.hasRemaining();
            }

            writeSizeZeroTimes = 0;

            // Write Body
            // 消息头数据发送完毕之后，发送消息内容
            if (!this.byteBufferHeader.hasRemaining()) {
                while (this.selectMappedBufferResult.getByteBuffer().hasRemaining()) {
                    // 发送消息内容
                    int writeSize = this.socketChannel.write(this.selectMappedBufferResult.getByteBuffer());
                    if (writeSize > 0) {
                        writeSizeZeroTimes = 0;
                        this.lastWriteTimestamp = HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now();
                    } else if (writeSize == 0) {
                        if (++writeSizeZeroTimes >= 3) {
                            break;
                        }
                    } else {
                        throw new Exception("ha master write body error < 0");
                    }
                }
            }

            boolean result = !this.byteBufferHeader.hasRemaining() && !this.selectMappedBufferResult.getByteBuffer().hasRemaining();

            if (!this.selectMappedBufferResult.getByteBuffer().hasRemaining()) {
                this.selectMappedBufferResult.release();
                this.selectMappedBufferResult = null;
            }

            return result;
        }

        @Override
        public String getServiceName() {
            return WriteSocketService.class.getSimpleName();
        }

        @Override
        public void shutdown() {
            super.shutdown();
        }
    }
}
