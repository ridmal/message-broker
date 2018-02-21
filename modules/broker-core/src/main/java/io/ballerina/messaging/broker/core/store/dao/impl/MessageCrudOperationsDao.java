/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package io.ballerina.messaging.broker.core.store.dao.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.ballerina.messaging.broker.common.data.types.FieldTable;
import io.ballerina.messaging.broker.core.BrokerException;
import io.ballerina.messaging.broker.core.ContentChunk;
import io.ballerina.messaging.broker.core.Message;
import io.ballerina.messaging.broker.core.Metadata;
import io.ballerina.messaging.broker.core.metrics.BrokerMetricManager;
import io.ballerina.messaging.broker.core.store.DbOperation;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.wso2.carbon.metrics.core.Timer.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Implements functionality required to manipulate messages in the storage.
 */
class MessageCrudOperationsDao extends BaseDao {

    private final BrokerMetricManager metricManager;

    MessageCrudOperationsDao(DataSource dataSource, BrokerMetricManager metricManager) {
        super(dataSource);
        this.metricManager = metricManager;
    }

    @SuppressFBWarnings(
            value = "RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT",
            justification = "Return value of context.stop() is not required.")
    public void persist(Connection connection, Collection<Message> messageList) throws SQLException {

        PreparedStatement metadataStmt = null;
        PreparedStatement contentStmt = null;
        PreparedStatement insertToQueueStmt = null;
        Context context = metricManager.startMessageWriteTimer();
        try {
            metadataStmt = connection.prepareStatement(RDBMSConstants.PS_INSERT_METADATA);
            contentStmt = connection.prepareStatement(RDBMSConstants.PS_INSERT_CONTENT);
            insertToQueueStmt = connection.prepareStatement(RDBMSConstants.PS_INSERT_INTO_QUEUE);

            for (Message message : messageList) {
                prepareMetadata(metadataStmt, message);
                prepareContent(contentStmt, message);
                prepareQueueAttachments(insertToQueueStmt, message);
            }
            metadataStmt.executeBatch();
            contentStmt.executeBatch();
            insertToQueueStmt.executeBatch();

        } catch (SQLException e) {
            throw new SQLException("Error persisting messages.", e);
        } finally {
            context.stop();
            close(metadataStmt);
            close(contentStmt);
            close(insertToQueueStmt);
        }
    }

    private void prepareQueueAttachments(PreparedStatement insertToQueueStmt, Message message) throws SQLException {
        long id = message.getInternalId();
        for (String queueName : message.getAttachedQueues()) {
            insertToQueueStmt.setLong(1, id);
            insertToQueueStmt.setString(2, queueName);
            insertToQueueStmt.addBatch();
        }
    }

    private void prepareContent(PreparedStatement contentStmt, Message message) throws SQLException {
        byte[] bytes;

        for (ContentChunk chunk : message.getContentChunks()) {
            contentStmt.setLong(1, message.getInternalId());
            contentStmt.setLong(2, chunk.getOffset());
            bytes = new byte[chunk.getBytes().readableBytes()];
            chunk.getBytes().getBytes(0, bytes);
            contentStmt.setBytes(3, bytes);
            contentStmt.addBatch();
        }
    }

    private void prepareMetadata(PreparedStatement metadataStmt, Message message) throws SQLException {
        Metadata metadata = message.getMetadata();
        metadataStmt.setLong(1, message.getInternalId());
        metadataStmt.setString(2, metadata.getExchangeName());
        metadataStmt.setString(3, metadata.getRoutingKey());
        metadataStmt.setLong(4, metadata.getContentLength());
        long size = metadata.getProperties().getSize() + metadata.getHeaders().getSize();
        byte[] bytes = new byte[(int) size];
        ByteBuf buffer = Unpooled.wrappedBuffer(bytes);

        try {
            buffer.resetWriterIndex();

            metadata.getProperties().write(buffer);
            metadata.getHeaders().write(buffer);

            metadataStmt.setBytes(5, bytes);
            metadataStmt.addBatch();
        } finally {
            buffer.release();
        }
    }

    public void detachFromQueue(Connection connection,
                                Collection<DbOperation> dbOperations) throws BrokerException {
        PreparedStatement statement = null;
        try {
            statement = connection.prepareStatement(RDBMSConstants.PS_DELETE_FROM_QUEUE);
            for (DbOperation dbOperation : dbOperations) {
                statement.setLong(1, dbOperation.getMessageId());
                statement.setString(2, dbOperation.getQueueName());
                statement.addBatch();
            }

            statement.executeBatch();
        } catch (SQLException e) {
            throw new BrokerException("Error detaching messages from queues.", e);
        } finally {
            close(statement);
        }
    }

    @SuppressFBWarnings(
            value = "RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT",
            justification = "Return value of context.stop() is not required.")
    public void delete(Connection connection, Collection<Long> internalIdList) throws BrokerException {
        PreparedStatement statement = null;
        Context context = metricManager.startMessageDeleteTimer();

        try {
            statement = connection.prepareStatement(RDBMSConstants.PS_DELETE_MESSAGE);
            for (Long internalId : internalIdList) {
                statement.setLong(1, internalId);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw new BrokerException("Error occurred while deleting messages", e);
        } finally {
            context.stop();
            close(statement);
        }
    }

    public Collection<Message> readAll(Connection connection, String queueName) throws BrokerException {
        Map<Long, Message> messageList = new LinkedHashMap<>();
        PreparedStatement statement = null;
        ResultSet resultSet = null;

        try {
            statement = connection.prepareStatement(RDBMSConstants.PS_SELECT_MESSAGES_FOR_QUEUE);
            statement.setString(1, queueName);
            resultSet = statement.executeQuery();
            while (resultSet.next()) {
                long messageId = resultSet.getLong(1);
                Message message = messageList.computeIfAbsent(messageId, k -> new Message(k, null));
                message.addOwnedQueue(resultSet.getString(2));
            }
            return messageList.values();
        } catch (SQLException e) {
            throw new BrokerException("Error occurred while reading messages", e);
        } finally {
            close(resultSet);
            close(statement);
        }
    }

    public Collection<Message> read(Connection connection, Map<Long, Message> messageMap) throws BrokerException {

        try (Context ignored = metricManager.startMessageReadTimer()) {
            if (!messageMap.isEmpty()) {
                String idList = getSQLFormattedIdList(messageMap.size());
                populateMessageWithMetadata(connection, idList, messageMap.keySet(), messageMap);
                populateContent(connection, idList, messageMap);
            }
            return messageMap.values();
        } catch (SQLException e) {
            throw new BrokerException("Error occurred while reading messages", e);
        }
    }

    private String getSQLFormattedIdList(int size) {
        StringBuilder paramList = new StringBuilder();
        paramList.append("?");

        for (int i = 1; i < size; i++) {
            paramList.append(",?");
        }
        return paramList.toString();
    }

    @SuppressFBWarnings("SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING")
    private void populateMessageWithMetadata(Connection connection,
                                             String idListAsString, Collection<Long> idList,
                                             Map<Long, Message> messageMap) throws SQLException, BrokerException {
        String metadataSql = "SELECT MESSAGE_ID, EXCHANGE_NAME, ROUTING_KEY, CONTENT_LENGTH, MESSAGE_METADATA "
                + " FROM MB_METADATA WHERE MESSAGE_ID IN (" + idListAsString + ") ORDER BY MESSAGE_ID";


        PreparedStatement selectMetadata = null;
        ResultSet metadataResultSet = null;

        try {
            selectMetadata = connection.prepareStatement(metadataSql);
            int i = 0;
            for (Long messageId : idList) {
                selectMetadata.setLong(++i, messageId);
            }

            metadataResultSet = selectMetadata.executeQuery();
            while (metadataResultSet.next()) {
                long messageId = metadataResultSet.getLong(1);
                String exchangeName = metadataResultSet.getString(2);
                String routingKey = metadataResultSet.getString(3);
                long contentLength = metadataResultSet.getLong(4);
                byte[] bytes = metadataResultSet.getBytes(5);
                ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
                try {
                    Metadata metadata = new Metadata(routingKey, exchangeName, contentLength);
                    metadata.setProperties(FieldTable.parse(buffer));
                    metadata.setHeaders(FieldTable.parse(buffer));

                    Message message = messageMap.get(messageId);
                    if (Objects.nonNull(message)) {
                        message.setMetadata(metadata);
                    } else {
                        message = new Message(messageId, metadata);
                        messageMap.put(messageId, message);
                    }
                } catch (Exception e) {
                    throw new BrokerException("Error occurred while parsing metadata properties", e);
                } finally {
                    buffer.release();
                }
            }
        } finally {
            close(metadataResultSet);
            close(selectMetadata);
        }
    }

    @SuppressFBWarnings("SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING")
    private void populateContent(Connection connection, String idList,
                                 Map<Long, Message> messageMap) throws SQLException {

        PreparedStatement selectContent = null;
        ResultSet contentResultSet = null;

        try {
            selectContent = connection.prepareStatement(
                    "SELECT MESSAGE_ID, CONTENT_OFFSET, MESSAGE_CONTENT FROM MB_CONTENT "
                            + "WHERE MESSAGE_ID IN(" + idList + ")");

            int i = 0;
            for (Long messageId : messageMap.keySet()) {
                selectContent.setLong(++i, messageId);
            }

            contentResultSet = selectContent.executeQuery();

            while (contentResultSet.next()) {
                long messageId = contentResultSet.getLong(1);
                int offset = contentResultSet.getInt(2);
                byte[] bytes = contentResultSet.getBytes(3);

                Message message = messageMap.get(messageId);
                if (message != null) {
                    message.addChunk(new ContentChunk(offset, Unpooled.copiedBuffer(bytes)));
                }
            }
        } finally {
            close(contentResultSet);
            close(selectContent);
        }
    }
}
