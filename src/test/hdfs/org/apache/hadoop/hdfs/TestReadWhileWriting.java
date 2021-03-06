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
package org.apache.hadoop.hdfs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.commons.logging.impl.Log4JLogger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.protocol.FSConstants;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.apache.hadoop.security.UnixUserGroupInformation;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.log4j.Level;
import org.junit.Assert;
import org.junit.Test;

/** Test reading from hdfs while a file is being written. */
public class TestReadWhileWriting {
  {
    ((Log4JLogger)FSNamesystem.LOG).getLogger().setLevel(Level.ALL);
    ((Log4JLogger)DFSClient.LOG).getLogger().setLevel(Level.ALL);
  }

  private static final String DIR = "/"
      + TestReadWhileWriting.class.getSimpleName() + "/";
  private static final int BLOCK_SIZE = 8192;
  
  /** Test reading while writing. */
  @Test
  public void testReadWhileWriting() throws Exception {
    final Configuration conf = new Configuration();
    //enable append
    conf.setBoolean("dfs.support.append", true);

    // create cluster
    final MiniDFSCluster cluster = new MiniDFSCluster(conf, 3, true, null);
    try {
      //change the lease soft limit to 1 second.
      final long leaseSoftLimit = 1000;
      cluster.setLeasePeriod(leaseSoftLimit, FSConstants.LEASE_HARDLIMIT_PERIOD);

      //wait for the cluster
      cluster.waitActive();
      final FileSystem fs = cluster.getFileSystem();
      final Path p = new Path(DIR, "file1");
      final int half = BLOCK_SIZE/2;

      //a. On Machine M1, Create file. Write half block of data.
      //   Invoke (DFSOutputStream).fsync() on the dfs file handle.
      //   Do not close file yet.
      {
        final FSDataOutputStream out = fs.create(p, true,
            fs.getConf().getInt("io.file.buffer.size", 4096),
            (short)3, BLOCK_SIZE);
        write(out, 0, half);

        //hflush
        ((DFSClient.DFSOutputStream)out.getWrappedStream()).hflush();
      }

      //b. On another machine M2, open file and verify that the half-block
      //   of data can be read successfully.
      checkFile(p, half, conf);

      /* TODO: enable the following when append is done.
      //c. On M1, append another half block of data.  Close file on M1.
      {
        //sleep to make sure the lease is expired the soft limit.
        Thread.sleep(2*leaseSoftLimit);

        FSDataOutputStream out = fs.append(p);
        write(out, 0, half);
        out.close();
      }

      //d. On M2, open file and read 1 block of data from it. Close file.
      checkFile(p, 2*half, conf);
      */
    } finally {
      cluster.shutdown();
    }
  }

  static private int userCount = 0;
  //check the file
  static void checkFile(Path p, int expectedsize, Configuration conf
      ) throws IOException {
    //open the file with another user account
    final Configuration conf2 = new Configuration(conf);
    final String username = UserGroupInformation.getCurrentUGI().getUserName()
        + "_" + ++userCount;
    UnixUserGroupInformation.saveToConf(conf2,
        UnixUserGroupInformation.UGI_PROPERTY_NAME,
        new UnixUserGroupInformation(username, new String[]{"supergroup"}));
    final FileSystem fs = FileSystem.get(conf2);
    final InputStream in = fs.open(p);

    //Is the data available?
    Assert.assertTrue(available(in, expectedsize));

    //Able to read?
    for(int i = 0; i < expectedsize; i++) {
      Assert.assertEquals((byte)i, (byte)in.read());  
    }

    in.close();
  }

  /** Write something to a file */
  private static void write(OutputStream out, int offset, int length
      ) throws IOException {
    final byte[] bytes = new byte[length];
    for(int i = 0; i < length; i++) {
      bytes[i] = (byte)(offset + i);
    }
    out.write(bytes);
  }

  /** Is the data available? */
  private static boolean available(InputStream in, int expectedsize
      ) throws IOException {
    final int available = in.available();
    System.out.println(" in.available()=" + available);
    Assert.assertTrue(available >= 0);
    Assert.assertTrue(available <= expectedsize);
    return available == expectedsize;
  }
}

