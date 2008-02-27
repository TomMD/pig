/*
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
package org.apache.pig.data;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.ArrayList;

import org.apache.pig.impl.util.Spillable;
import org.apache.pig.backend.hadoop.executionengine.mapreduceExec.PigMapReduce;

/**
 * A collection of Tuples.  A DataBag may or may not fit into memory.
 * DataBag extends spillable, which means that it registers with a memory
 * manager.  By default, it attempts to keep all of its contents in memory.
 * If it is asked by the memory manager to spill to disk (by a call to
 * spill()), it takes whatever it has in memory, opens a spill file, and
 * writes the contents out.  This may happen multiple times.  The bag
 * tracks all of the files it's spilled to.
 * 
 * DataBag provides an Iterator interface, that allows callers to read
 * through the contents.  The iterators are aware of the data spilling.
 * They have to be able to handle reading from files, as well as the fact
 * that data they were reading from memory may have been spilled to disk
 * underneath them.
 *
 * The DataBag interface assumes that all data is written before any is
 * read.  That is, a DataBag cannot be used as a queue.  If data is written
 * after data is read, the results are undefined.  This condition is not
 * checked on each add or read, for reasons of speed.  Caveat emptor.
 *
 * Since spills are asynchronous (the memory manager requesting a spill
 * runs in a separate thread), all operations dealing with the mContents
 * Collection (which is the collection of tuples contained in the bag) have
 * to be synchronized.  This means that reading from a DataBag is currently
 * serialized.  This is ok for the moment because pig execution is
 * currently single threaded.  A ReadWriteLock was experimented with, but
 * it was found to be about 10x slower than using the synchronize keyword.
 * If pig changes its execution model to be multithreaded, we may need to
 * return to this issue, as synchronizing reads will most likely defeat the
 * purpose of multi-threading execution.
 *
 * DataBag come in several types, default, sorted, and distinct.  The type
 * must be chosen up front, there is no way to convert a bag on the fly.
 * 
 * This is the default implementation.  Users are free to provide their
 * own implementation, but they should keep in mind the need to support
 * bags that do not fit in memory, and handle spilling in an efficient
 * manner.
 */
public abstract class DefaultAbstractBag implements DataBag {
    // Container that holds the tuples. Actual object instantiated by
    // subclasses.
    protected Collection<Tuple> mContents;

    // Spill files we've created.  These need to be removed in finalize.
    protected ArrayList<File> mSpillFiles;

    // Total size, including tuples on disk.  Stored here so we don't have
    // to run through the disk when people ask.
    protected long mSize = 0;

    protected boolean mMemSizeChanged = false;

    protected long mMemSize = 0;

    /**
     * Get the number of elements in the bag, both in memory and on disk.
     */
    public long size() {
        return mSize;
    }

    /**
     * Add a tuple to the bag.
     * @param t tuple to add.
     */
    public void add(Tuple t) {
        synchronized (mContents) {
            mMemSizeChanged = true;
            mSize++;
            mContents.add(t);
        }
    }

    /**
     * Add contents of a bag to the bag.
     * @param b bag to add contents of.
     */
    public void addAll(DataBag b) {
        synchronized (mContents) {
            mMemSizeChanged = true;
            mSize += b.size();
            Iterator<Tuple> i = b.iterator();
            while (i.hasNext()) mContents.add(i.next());
        }
    }

    /**
     * Add contents of a container to the bag.
     * @param c Collection to add contents of.
     */
    public void addAll(Collection<Tuple> c) {
        synchronized (mContents) {
            mMemSizeChanged = true;
            mSize += c.size();
            Iterator<Tuple> i = c.iterator();
            while (i.hasNext()) mContents.add(i.next());
        }
    }

    /**
     * Return the size of memory usage.
     */
    @Override
    public long getMemorySize() {
        if (!mMemSizeChanged) return mMemSize;

        long used = 0;
        // I can't afford to talk through all the tuples every time the
        // memory manager wants to know if it's time to dump.  Just sample
        // the first 100 and see what we get.  This may not be 100%
        // accurate, but it's just an estimate anyway.
        int j;
        int numInMem = 0;
        synchronized (mContents) {
            numInMem = mContents.size();
            // Measure only what's in memory, not what's on disk.
            Iterator<Tuple> i = mContents.iterator();
            for (j = 0; i.hasNext() && j < 100; j++) { 
                used += i.next().getMemorySize();
            }
        }

        if (numInMem > 100) {
            // Estimate the per tuple size.  Do it in integer arithmetic
            // (even though it will be slightly less accurate) for speed.
            used /= j;
            used *= numInMem;
        }

        mMemSize = used;
        mMemSizeChanged = false;
        return used;
    }

    /**
     * Clear out the contents of the bag, both on disk and in memory.
     * Any attempts to read after this is called will produce undefined
     * results.
     */
    public void clear() {
        synchronized (mContents) {
            mContents.clear();
            if (mSpillFiles != null) {
                for (int i = 0; i < mSpillFiles.size(); i++) {
                    mSpillFiles.get(i).delete();
                }
                mSpillFiles.clear();
            }
            mSize = 0;
        }
    }

    /**
     * This method is potentially very expensive since it may require a
     * sort of the bag; don't call it unless you have to.
     */
    public int compareTo(Object other) {
        if (this == other)
            return 0;
        if (other instanceof DataBag) {
            DataBag bOther = (DataBag) other;
            if (this.size() != bOther.size()) {
                if (this.size() > bOther.size()) return 1;
                else return -1;
            }

            // Ugh, this is bogus.  But I have to know if two bags have the
            // same tuples, regardless of order.  Hopefully most of the
            // time the size check above will prevent this.
            // If either bag isn't already sorted, create a sorted bag out
            // of it so I can guarantee order.
            DataBag thisClone;
            DataBag otherClone;
            if (this instanceof SortedDataBag ||
                    this instanceof DistinctDataBag) {
                thisClone = this;
            } else {
                thisClone = new SortedDataBag(null);
                Iterator<Tuple> i = iterator();
                while (i.hasNext()) thisClone.add(i.next());
            }
            if (other instanceof SortedDataBag ||
                    this instanceof DistinctDataBag) {
                otherClone = bOther;
            } else {
                otherClone = new SortedDataBag(null);
                Iterator<Tuple> i = bOther.iterator();
                while (i.hasNext()) otherClone.add(i.next());
            }
            Iterator<Tuple> thisIt = thisClone.iterator();
            Iterator<Tuple> otherIt = otherClone.iterator();
            while (thisIt.hasNext() && otherIt.hasNext()) {
                Tuple thisT = thisIt.next();
                Tuple otherT = otherIt.next();
                
                int c = thisT.compareTo(otherT);
                if (c != 0) return c;
            }
            
            return 0;   // if we got this far, they must be equal
        } else {
            return DataType.compare(this, other);
        }
    }

    @Override
    public boolean equals(Object other) {
        return compareTo(other) == 0;
    }

    /**
     * Write a bag's contents to disk.
     * @param out DataOutput to write data to.
     * @throws IOException (passes it on from underlying calls).
     */
    @Override
    public void write(DataOutput out) throws IOException {
        // We don't care whether this bag was sorted or distinct because
        // using the iterator to write it will guarantee those things come
        // correctly.  And on the other end there'll be no reason to waste
        // time re-sorting or re-applying distinct.
        out.writeLong(size());
        Iterator<Tuple> it = iterator();
        while (it.hasNext()) {
            Tuple item = it.next();
            item.write(out);
        }    
    }
 
    /**
     * Read a bag from disk.
     * @param in DataInput to read data from.
     * @throws IOException (passes it on from underlying calls).
     */
    public void readFields(DataInput in) throws IOException {
        long size = in.readLong();
        
        for (long i = 0; i < size; i++) {
            Object o = DataReaderWriter.readDatum(in);
            add((Tuple)o);
        }
    }

    /**
     * This is used by FuncEvalSpec.FakeDataBag.
     * @param stale Set stale state.
     */
    public void markStale(boolean stale)
    {
    }

    /**
     * Write the bag into a string. */
    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append('{');
        Iterator<Tuple> it = iterator();
        while ( it.hasNext() ) {
            Tuple t = it.next();
            String s = t.toString();
            sb.append(s);
            if (it.hasNext()) sb.append(", ");
        }
        sb.append('}');
        return sb.toString();
    }

    @Override
    public int hashCode() {
        int hash = 1;
        Iterator<Tuple> i = iterator();
        while (i.hasNext()) {
            // Use 37 because we want a prime, and tuple uses 31.
            hash = 37 * hash + i.next().hashCode();
        }
        return hash;
    }

    /**
     * Need to override finalize to clean out the mSpillFiles array.
     */
    @Override
    protected void finalize() {
        if (mSpillFiles != null) {
            for (int i = 0; i < mSpillFiles.size(); i++) {
                mSpillFiles.get(i).delete();
            }
        }
    }

    /**
     * Get a file to spill contents to.  The file will be registered in the
     * mSpillFiles array.
     * @return stream to write tuples to.
     */
    protected DataOutputStream getSpillFile() throws IOException {
        if (mSpillFiles == null) {
            // We want to keep the list as small as possible.
            mSpillFiles = new ArrayList<File>(1);
        }

        File f = File.createTempFile("pigbag", null);
        f.deleteOnExit();
        mSpillFiles.add(f);
        return new DataOutputStream(new BufferedOutputStream(
            new FileOutputStream(f)));
    }

    /**
     * Report progress to HDFS.
     */
    protected void reportProgress() {
        if (PigMapReduce.reporter != null) {
            PigMapReduce.reporter.progress();
        }
    }

    public static abstract class BagDelimiterTuple extends DefaultTuple{}
    public static class StartBag extends BagDelimiterTuple{}
    
    public static class EndBag extends BagDelimiterTuple{}
    
    public static final Tuple startBag = new StartBag();
    public static final Tuple endBag = new EndBag();

    protected static final int MAX_SPILL_FILES = 100;
 
}
