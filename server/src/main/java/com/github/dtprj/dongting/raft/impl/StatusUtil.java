/*
 * Copyright The Dongting Project
 *
 * The Dongting Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.github.dtprj.dongting.raft.impl;

import com.github.dtprj.dongting.common.CloseUtil;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.raft.client.RaftException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;
import java.util.zip.CRC32C;

/**
 * @author huangli
 */
public class StatusUtil {
    private static final DtLog log = DtLogs.getLogger(StatusUtil.class);

    private static final int FILE_LENGTH = 512;
    private static final int CRC_HEX_LENGTH = 8;
    private static final int CONTENT_START_POS = CRC_HEX_LENGTH + 2;
    private static final int CONTENT_LENGTH = FILE_LENGTH - CONTENT_START_POS;
    private static final String currentTermKey = "currentTerm";
    private static final String votedForKey = "votedFor";

    public static void initStatusFileChannel(String dataDir, String filename, RaftStatus raftStatus) {
        RandomAccessFile statusFile = null;
        FileLock lock = null;
        try {
            File dir = new File(dataDir);
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    throw new RaftException("make dir failed: " + dir.getPath());
                }
                log.info("make dir: {}", dir.getPath());
            }
            File file = new File(dir, filename);
            boolean create = !file.exists();
            if (create) {
                if (!file.createNewFile()) {
                    throw new RaftException("create status file failed: " + file.getPath());
                }
            }
            statusFile = new RandomAccessFile(file, "rw");
            FileChannel statusChannel = statusFile.getChannel();
            lock = statusChannel.lock();
            if (!create) {
                log.info("loading status file: {}", file.getPath());
                if (file.length() != FILE_LENGTH) {
                    throw new RaftException("bad status file length: " + file.length());
                }
                ByteBuffer buf = ByteBuffer.allocate(FILE_LENGTH);
                if (statusChannel.read(buf) != FILE_LENGTH) {
                    throw new RaftException("read length not " + FILE_LENGTH);
                }
                byte[] bytes = buf.array();

                CRC32C crc32c = new CRC32C();

                crc32c.update(bytes, CONTENT_START_POS, CONTENT_LENGTH);
                int expectCrc = (int) crc32c.getValue();

                int actualCrc = Integer.parseInt(new String(bytes, 0, 8, StandardCharsets.UTF_8), 16);

                if (actualCrc != expectCrc) {
                    throw new RaftException("bad status file crc: " + actualCrc + ", expect: " + expectCrc);
                }

                Properties p = new Properties();
                p.load(new StringReader(new String(bytes, CONTENT_START_POS, CONTENT_LENGTH, StandardCharsets.UTF_8)));

                raftStatus.setCurrentTerm(Integer.parseInt(p.getProperty(currentTermKey).trim()));
                raftStatus.setVotedFor(Integer.parseInt(p.getProperty(votedForKey).trim()));
            }

            raftStatus.setStatusFile(file);
            raftStatus.setRandomAccessStatusFile(statusFile);
            raftStatus.setStatusChannel(statusChannel);
            raftStatus.setStatusFileLock(lock);
        } catch (IOException e) {
            CloseUtil.close(lock, statusFile);
            throw new RaftException(e);
        } catch (RaftException e) {
            CloseUtil.close(lock, statusFile);
            throw e;
        }
    }

    public static void updateStatusFile(RaftStatus raftStatus) {
        updateStatusFileImpl(raftStatus, true);
    }

    private static void updateStatusFileImpl(RaftStatus raftStatus, boolean firstTry) {
        try {
            if (firstTry && raftStatus.isSaving()) {
                // prevent concurrent saving or saving actions more and more
                return;
            }
            if (raftStatus.isStop()) {
                return;
            }
            raftStatus.setSaving(true);
            FileChannel channel = raftStatus.getStatusChannel();
            channel.position(0);
            Properties p = new Properties();
            p.setProperty(currentTermKey, String.valueOf(raftStatus.getCurrentTerm()));
            p.setProperty(votedForKey, String.valueOf(raftStatus.getVotedFor()));
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
            p.store(bos, null);
            byte[] propertiesBytes = bos.toByteArray();
            byte[] fileContent = new byte[FILE_LENGTH];
            Arrays.fill(fileContent, (byte) ' ');
            System.arraycopy(propertiesBytes, 0, fileContent, CONTENT_START_POS, propertiesBytes.length);
            fileContent[CONTENT_START_POS - 2] = '\r';
            fileContent[CONTENT_START_POS - 1] = '\n';

            CRC32C crc32c = new CRC32C();
            crc32c.update(fileContent, CONTENT_START_POS, CONTENT_LENGTH);
            int crc = (int) crc32c.getValue();
            String crcHex = String.format("%08x", crc);
            byte[] crcBytes = crcHex.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(crcBytes, 0, fileContent, 0, CRC_HEX_LENGTH);
            ByteBuffer buf = ByteBuffer.wrap(fileContent);
            channel.write(buf);
            channel.force(false);
            raftStatus.setSaving(false);
            log.info("{}saving raft status file success: {}", firstTry ? "" : "retry ",
                    raftStatus.getStatusFile().getName());
        } catch (IOException e) {
            log.error("update status file failed, firstTry after 1000ms, file={}",
                    raftStatus.getStatusFile().getPath(), e);
            raftStatus.getRaftExecutor().schedule(() -> updateStatusFileImpl(raftStatus, false), 1000);
        }
    }
}
