/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode.snapshot;

import java.io.DataOutputStream;
import java.io.IOException;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfo;
import org.apache.hadoop.hdfs.server.namenode.FSImageSerialization;
import org.apache.hadoop.hdfs.server.namenode.INode.BlocksMapUpdateInfo;
import org.apache.hadoop.hdfs.server.namenode.INodeFile;

/**
 * {@link INodeFile} with a link to the next element.
 * The link of all the snapshot files and the original file form a circular
 * linked list so that all elements are accessible by any of the elements.
 */
@InterfaceAudience.Private
public interface FileWithSnapshot {
  /**
   * The difference of an {@link INodeFile} between two snapshots.
   */
  public static class FileDiff extends AbstractINodeDiff<INodeFile, FileDiff> {
    /** The file size at snapshot creation time. */
    private final long fileSize;

    FileDiff(Snapshot snapshot, INodeFile file) {
      super(snapshot, null, null);
      fileSize = file.computeFileSize();
    }

    /** Constructor used by FSImage loading */
    FileDiff(Snapshot snapshot, INodeFile snapshotINode,
        FileDiff posteriorDiff, long fileSize) {
      super(snapshot, snapshotINode, posteriorDiff);
      this.fileSize = fileSize;
    }

    /** @return the file size in the snapshot. */
    public long getFileSize() {
      return fileSize;
    }

    @Override
    void combinePosteriorAndCollectBlocks(INodeFile currentINode,
        FileDiff posterior, BlocksMapUpdateInfo collectedBlocks) {
      Util.collectBlocksAndClear((FileWithSnapshot)currentINode, collectedBlocks);
    }
    
    @Override
    public String toString() {
      return super.toString() + " fileSize=" + fileSize + ", rep="
          + (snapshotINode == null? "?": snapshotINode.getFileReplication());
    }

    /** Serialize fields to out */
    void write(DataOutputStream out) throws IOException {
      writeSnapshotPath(out);
      out.writeLong(fileSize);

      // write snapshotINode
      if (snapshotINode != null) {
        out.writeBoolean(true);
        FSImageSerialization.writeINodeFile(snapshotINode, out, true);
      } else {
        out.writeBoolean(false);
      }
    }
  }

  static class FileDiffFactory
      extends AbstractINodeDiff.Factory<INodeFile, FileDiff> {
    static final FileDiffFactory INSTANCE = new FileDiffFactory();

    @Override
    FileDiff createDiff(Snapshot snapshot, INodeFile file) {
      return new FileDiff(snapshot, file);
    }

    @Override
    INodeFile createSnapshotCopy(INodeFile currentINode) {
      final INodeFile copy = new INodeFile(currentINode);
      copy.setBlocks(null);
      return copy;
    }
  }

  /**
   * A list of {@link FileDiff}.
   */
  public static class FileDiffList
      extends AbstractINodeDiffList<INodeFile, FileDiff> {
  }

  /** @return the {@link INodeFile} view of this object. */
  public INodeFile asINodeFile();

  /** @return the file diff list. */
  public FileDiffList getDiffs();

  /** Is the current file deleted? */
  public boolean isCurrentFileDeleted();

  /** Utility methods for the classes which implement the interface. */
  public static class Util {
    /** 
     * @return block replication, which is the max file replication among
     *         the file and the diff list.
     */
    public static short getBlockReplication(final FileWithSnapshot file) {
      short max = file.isCurrentFileDeleted()? 0
          : file.asINodeFile().getFileReplication();
      for(FileDiff d : file.getDiffs()) {
        if (d.snapshotINode != null) {
          final short replication = d.snapshotINode.getFileReplication();
          if (replication > max) {
            max = replication;
          }
        }
      }
      return max;
    }

    /**
     * If some blocks at the end of the block list no longer belongs to
     * any inode, collect them and update the block list.
     */
    static void collectBlocksAndClear(final FileWithSnapshot file,
        final BlocksMapUpdateInfo info) {
      // check if everything is deleted.
      if (file.isCurrentFileDeleted()
          && file.getDiffs().asList().isEmpty()) {
        file.asINodeFile().destroySelfAndCollectBlocks(info);
        return;
      }

      // find max file size.
      final long max;
      if (file.isCurrentFileDeleted()) {
        final FileDiff last = file.getDiffs().getLast();
        max = last == null? 0: last.fileSize;
      } else { 
        max = file.asINodeFile().computeFileSize();
      }

      collectBlocksBeyondMax(file, max, info);
    }

    private static void collectBlocksBeyondMax(final FileWithSnapshot file,
        final long max, final BlocksMapUpdateInfo collectedBlocks) {
      final BlockInfo[] oldBlocks = file.asINodeFile().getBlocks();
      if (oldBlocks != null) {
        //find the minimum n such that the size of the first n blocks > max
        int n = 0;
        for(long size = 0; n < oldBlocks.length && max > size; n++) {
          size += oldBlocks[n].getNumBytes();
        }
        
        // starting from block n, the data is beyond max.
        if (n < oldBlocks.length) {
          // resize the array.  
          final BlockInfo[] newBlocks;
          if (n == 0) {
            newBlocks = null;
          } else {
            newBlocks = new BlockInfo[n];
            System.arraycopy(oldBlocks, 0, newBlocks, 0, n);
          }
          
          // set new blocks
          file.asINodeFile().setBlocks(newBlocks);

          // collect the blocks beyond max.  
          if (collectedBlocks != null) {
            for(; n < oldBlocks.length; n++) {
              collectedBlocks.addDeleteBlock(oldBlocks[n]);
            }
          }
        }
      }
    }
  }
}
