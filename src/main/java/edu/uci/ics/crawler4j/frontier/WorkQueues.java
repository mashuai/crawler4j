/**
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

package edu.uci.ics.crawler4j.frontier;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sleepycat.je.Cursor;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.Transaction;

import edu.uci.ics.crawler4j.url.WebURL;
import edu.uci.ics.crawler4j.util.Util;

/**
 * @author Yasser Ganjisaffar
 * URL队列
 */
public class WorkQueues {
  private static final Logger logger = LoggerFactory.getLogger(WorkQueues.class);

  protected Database urlsDB = null;
  protected Environment env;

  protected boolean resumable;

  protected WebURLTupleBinding webURLBinding;

  protected final Object mutex = new Object();

  public WorkQueues(Environment env, String dbName, boolean resumable) {
    this.env = env;
    this.resumable = resumable;
    DatabaseConfig dbConfig = new DatabaseConfig();
    dbConfig.setAllowCreate(true);
    dbConfig.setTransactional(resumable);
    dbConfig.setDeferredWrite(!resumable);
    urlsDB = env.openDatabase(null, dbName, dbConfig);
    webURLBinding = new WebURLTupleBinding();
  }

    /*
    获取最多max个WebURL
     */
  public List<WebURL> get(int max) {
    synchronized (mutex) {
      List<WebURL> results = new ArrayList<>(max);

      Cursor cursor = null;
      DatabaseEntry key = new DatabaseEntry();
      DatabaseEntry value = new DatabaseEntry();
      Transaction txn;
      if (resumable) {
        txn = env.beginTransaction(null, null);
      } else {
        txn = null;
      }
      try {
        cursor = urlsDB.openCursor(txn, null);
        OperationStatus result = cursor.getFirst(key, value, null);

        int matches = 0;
        while ((matches < max) && (result == OperationStatus.SUCCESS)) {
          if (value.getData().length > 0) {
            results.add(webURLBinding.entryToObject(value));
            matches++;
          }
          result = cursor.getNext(key, value, null);
        }
      } catch (DatabaseException e) {
        if (txn != null) {
          txn.abort();
          txn = null;
        }
        throw e;
      } finally {
        if (cursor != null) {
          cursor.close();
        }
        if (txn != null) {
          txn.commit();
        }
      }
      return results;
    }
  }
    /*
        删除count个url。
     */
  public void delete(int count) {
    synchronized (mutex) {

      Cursor cursor = null;
      DatabaseEntry key = new DatabaseEntry();
      DatabaseEntry value = new DatabaseEntry();
      Transaction txn;
      if (resumable) {
        txn = env.beginTransaction(null, null);
      } else {
        txn = null;
      }
      try {
        cursor = urlsDB.openCursor(txn, null);
        OperationStatus result = cursor.getFirst(key, value, null);

        int matches = 0;
        while ((matches < count) && (result == OperationStatus.SUCCESS)) {
          cursor.delete();
          matches++;
          result = cursor.getNext(key, value, null);
        }
      } catch (DatabaseException e) {
        if (txn != null) {
          txn.abort();
          txn = null;
        }
        throw e;
      } finally {
        if (cursor != null) {
          cursor.close();
        }
        if (txn != null) {
          txn.commit();
        }
      }
    }
  }

  /*
   * The key that is used for storing URLs determines the order
   * they are crawled. Lower key values results in earlier crawling.
   * Here our keys are 6 bytes. The first byte comes from the URL priority.
   * The second byte comes from depth of crawl at which this URL is first found.
   * The rest of the 4 bytes come from the docid of the URL. As a result,
   * URLs with lower priority numbers will be crawled earlier. If priority
   * numbers are the same, those found at lower depths will be crawled earlier.
   * If depth is also equal, those found earlier (therefore, smaller docid) will
   * be crawled earlier.
   * WebURL的key
   * 6字节
   * 1，优先级，越小优先级越高，如果相同比较第二字节。
   * 2，深度，是从哪一级获取的，越小优先级越高。如果相同比较剩下的字节
   * 3-6，docid，越小，优先级越高。
   */
  protected static DatabaseEntry getDatabaseEntryKey(WebURL url) {
    byte[] keyData = new byte[6];
    keyData[0] = url.getPriority();
    keyData[1] = ((url.getDepth() > Byte.MAX_VALUE) ? Byte.MAX_VALUE : (byte) url.getDepth());
    Util.putIntInByteArray(url.getDocid(), keyData, 2);
    return new DatabaseEntry(keyData);
  }

    /*
    加入新的URL
     */
  public void put(WebURL url) {
    DatabaseEntry value = new DatabaseEntry();
    webURLBinding.objectToEntry(url, value);
    Transaction txn;
    if (resumable) {
      txn = env.beginTransaction(null, null);
    } else {
      txn = null;
    }
    urlsDB.put(txn, getDatabaseEntryKey(url), value);
    if (resumable) {
      if (txn != null) {
        txn.commit();
      }
    }
  }
  /*
  获取urls的长度
   */
  public long getLength() {
    try {
      return urlsDB.count();
    } catch (Exception e) {
      logger.error("Error in UrlsDB", e);
      return -1;
    }
  }

  public void close() {
    try {
      urlsDB.close();
    } catch (DatabaseException e) {
      logger.error("Error in UrlsDB", e);
    }
  }
}