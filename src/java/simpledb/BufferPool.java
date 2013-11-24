package simpledb;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
/**
 * BufferPool manages the reading and writing of pages into memory from
 * disk. Access methods call into it to retrieve pages, and it fetches
 * pages from the appropriate location.
 * <p>
 * The BufferPool is also responsible for locking;  when a transaction fetches
 * a page, BufferPool checks that the transaction has the appropriate
 * locks to read/write the page.
 */
public class BufferPool {
    /** Bytes per page, including header. */
    public static final int PAGE_SIZE = 4096;

    /** Default number of pages passed to the constructor. This is used by
    other classes. BufferPool should use the numPages argument to the
    constructor instead. */
    public static final int DEFAULT_PAGES = 50;

    public class LockManager {
        ConcurrentHashMap <PageId,Permissions>pageLock;
        ConcurrentHashMap <PageId,ArrayList<TransactionId>>pageTransactions;
        ConcurrentHashMap <TransactionId,ArrayList<PageId>>transactionPages;
        public LockManager (){
            pageLock=new ConcurrentHashMap<PageId,Permissions> ();
            pageTransactions=new ConcurrentHashMap<PageId,ArrayList<TransactionId>>();
            transactionPages=new ConcurrentHashMap<TransactionId,ArrayList<PageId>>();
        }
        public synchronized boolean lockPage(PageId pid,TransactionId tid,Permissions perm){
            Permissions currPerm=pageLock.get(pid);
            ArrayList<TransactionId>temp=pageTransactions.get(pid);
            if(currPerm==null || (currPerm==perm && perm==Permissions.READ_ONLY) || (currPerm==Permissions.READ_WRITE && temp.size()==1 && temp.get(0).equals(tid)) || (currPerm==Permissions.READ_ONLY && temp.size()==1 && temp.get(0).equals(tid))){
                pageLock.put(pid,perm);
                if(transactionPages.get(tid)==null){
                    ArrayList<PageId> pids=new ArrayList<PageId>();
                    pids.add(pid);
                    transactionPages.put(tid,pids);
                } else {
                    ArrayList<PageId>pidTemp=transactionPages.get(tid);
                    if(!pidTemp.contains(pid)){
                        pidTemp.add(pid);
                    }
                }
                if(pageTransactions.get(pid)==null){
                    ArrayList<TransactionId> tids=new ArrayList<TransactionId>();
                    tids.add(tid);
                    pageTransactions.put(pid,tids);
                } else {
                    if(!temp.contains(tid)){
                        temp.add(tid);
                    }
                }
                return true;
            }
            return false;
        }
        public Permissions getLockType(PageId pid){
            return pageLock.get(pid);
        }
        public boolean isLocked(TransactionId tid,PageId pid){
            ArrayList<TransactionId> temp=pageTransactions.get(pid);
            if(temp!=null && temp.contains(tid)){
                return true;
            } else {
                return false;
            }
        }
        public void releaseLock(PageId pid,TransactionId tid){
            ArrayList<TransactionId> temp=pageTransactions.get(pid);
            ArrayList<PageId> temp1=transactionPages.get(tid);
            if(temp == null){
                return;
            }
            if(temp1==null){
                return;
            }
            if(temp1.size()==1){
                transactionPages.remove(tid);
                temp1.remove(pid);
            } else {
                temp1.remove(pid);
            }
            if(temp.size()==1){
                pageTransactions.remove(pid);
                pageLock.remove(pid);
                temp.remove(tid);
            } else {
                temp.remove(tid);
            }
        }
        public ArrayList<PageId> getPageIds(TransactionId tid){
            return transactionPages.get(tid);
        }

    }

    
    ConcurrentHashMap <PageId,Page> pool;
    ConcurrentHashMap <PageId,ArrayList> lockManager;
    LockManager manager;
    int pages;
    /**
     * Creates a BufferPool that caches up to numPages pages.
     *
     * @param numPages maximum number of pages in this buffer pool.
     */
    public BufferPool(int numPages) {
        // some code goes here
        pool=new ConcurrentHashMap<PageId,Page>(numPages);
        pages=numPages;
        manager=new LockManager();
        
    }

    /**
     * Retrieve the specified page with the associated permissions.
     * Will acquire a lock and may block if that lock is held by another
     * transaction.
     * <p>
     * The retrieved page should be looked up in the buffer pool.  If it
     * is present, it should be returned.  If it is not present, it should
     * be added to the buffer pool and returned.  If there is insufficient
     * space in the buffer pool, an page should be evicted and the new page
     * should be added in its place.
     *
     * @param tid the ID of the transaction requesting the page
     * @param pid the ID of the requested page
     * @param perm the requested permissions on the page
     */
    public  Page getPage(TransactionId tid, PageId pid, Permissions perm)
        throws TransactionAbortedException, DbException {
        // some code goes here
        int count=0;
        while(!manager.lockPage(pid,tid,perm)){
            count++;
            if(count==6){
                throw new TransactionAbortedException();
            }
            try{
                Thread.sleep(50);
            } catch(InterruptedException e){
            }
        }
        if(pool.containsKey(pid)){
            return pool.get(pid);
        } else {
            if(pool.size()==pages){
                evictPage();
            }
            DbFile temp=Database.getCatalog().getDbFile(pid.getTableId());
            Page temp1=temp.readPage(pid);
            pool.put(pid,temp1);
            return temp1;
            
        }
    }

    /**
     * Releases the lock on a page.
     * Calling this is very risky, and may result in wrong behavior. Think hard
     * about who needs to call this and why, and why they can run the risk of
     * calling it.
     *
     * @param tid the ID of the transaction requesting the unlock
     * @param pid the ID of the page to unlock
     */
    public  void releasePage(TransactionId tid, PageId pid) {
        // some code goes here
        // not necessary for proj1
        manager.releaseLock(pid,tid);

    }

    /**
     * Release all locks associated with a given transaction.
     *
     * @param tid the ID of the transaction requesting the unlock
     */
    public void transactionComplete(TransactionId tid) throws IOException {
        // some code goes here
        // not necessary for proj1
        transactionComplete(tid,true);
    }

    /** Return true if the specified transaction has a lock on the specified page */
    public boolean holdsLock(TransactionId tid, PageId p) {
        // some code goes here
        // not necessary for proj1
        return manager.isLocked(tid,p);
    }

    /**
     * Commit or abort a given transaction; release all locks associated to
     * the transaction.
     *
     * @param tid the ID of the transaction requesting the unlock
     * @param commit a flag indicating whether we should commit or abort
     */
    public void transactionComplete(TransactionId tid, boolean commit)
        throws IOException {
        // some code goes here
        // not necessary for proj1
        ArrayList<PageId>pids=manager.getPageIds(tid);
        if(pids==null){
            return;
        }
        int size=pids.size();
        if(commit){
            flushPages(tid);
        } else {
            while(!pids.isEmpty()){
                PageId temp=pids.get(0);
                HeapPage tempPage=(HeapPage)pool.get(temp);
                if(tempPage != null){
                    tempPage=tempPage.getBeforeImage();
                    pool.put(temp,tempPage);
                }
                releasePage(tid,temp);
            }
        }
    }

    /**
     * Add a tuple to the specified table behalf of transaction tid.  Will
     * acquire a write lock on the page the tuple is added to(Lock 
     * acquisition is not needed for lab2). May block if the lock cannot 
     * be acquired.
     * 
     * Marks any pages that were dirtied by the operation as dirty by calling
     * their markDirty bit, and updates cached versions of any pages that have 
     * been dirtied so that future requests see up-to-date pages. 
     *
     * @param tid the transaction adding the tuple
     * @param tableId the table to add the tuple to
     * @param t the tuple to add
     */
    public synchronized void insertTuple(TransactionId tid, int tableId, Tuple t)
        throws DbException, IOException, TransactionAbortedException {
        // some code goes here
        // not necessary for proj1
        Database.getCatalog().getDbFile(tableId).insertTuple(tid,t);

    }

    /**
     * Remove the specified tuple from the buffer pool.
     * Will acquire a write lock on the page the tuple is removed from. May block if
     * the lock cannot be acquired.
     *
     * Marks any pages that were dirtied by the operation as dirty by calling
     * their markDirty bit.  Does not need to update cached versions of any pages that have 
     * been dirtied, as it is not possible that a new page was created during the deletion
     * (note difference from addTuple).
     *
     * @param tid the transaction adding the tuple.
     * @param t the tuple to add
     */
    public synchronized void deleteTuple(TransactionId tid, Tuple t)
        throws DbException, TransactionAbortedException {
        // some code goes here
        // not necessary for proj1
        Database.getCatalog().getDbFile(t.getRecordId().getPageId().getTableId()).deleteTuple(tid,t);
    }

    /**
     * Flush all dirty pages to disk.
     * NB: Be careful using this routine -- it writes dirty data to disk so will
     *     break simpledb if running in NO STEAL mode.
     */
    public synchronized void flushAllPages() throws IOException {
        // some code goes here
        // not necessary for proj1
        Enumeration <PageId>iter=pool.keys();
        while(iter.hasMoreElements()){
            flushPage((PageId)iter.nextElement());
        }

    }

    /** Remove the specific page id from the buffer pool.
        Needed by the recovery manager to ensure that the
        buffer pool doesn't keep a rolled back page in its
        cache.
    */
    public synchronized void discardPage(PageId pid) {
        // some code goes here

    }

    /**
     * Flushes a certain page to disk
     * @param pid an ID indicating the page to flush
     */
    private synchronized  void flushPage(PageId pid) throws IOException {
        // some code goes here
        // not necessary for proj1
        HeapFile temp=(HeapFile)Database.getCatalog().getDbFile(pid.getTableId());
        HeapPage temp1=(HeapPage)pool.get(pid);
        if(temp1.isDirty()!=null){
            temp.writePage(temp1);
        }

    }

    /** Write all pages of the specified transaction to disk.
     */
    public synchronized  void flushPages(TransactionId tid) throws IOException {
        // some code goes here
        // not necessary for proj1
         Enumeration<PageId> iter=pool.keys();
         while(iter.hasMoreElements()){
             HeapPageId temp1=(HeapPageId)iter.nextElement();
             HeapPage temp=(HeapPage)pool.get(temp1);
             if(tid != null && temp!=null && temp.isDirty() !=null && tid.equals(temp.isDirty())){
                 flushPage(temp1);
             }
             releasePage(tid,temp1);
         }


    }

    /**
     * Discards a page from the buffer pool.
     * Flushes the page to disk to ensure dirty pages are updated on disk.
     */
    private synchronized  void evictPage() throws DbException {
        // some code goes here
        // not necessary for proj1
        
        Enumeration<Page> e=pool.elements();
        while(e.hasMoreElements()){
            Page temp=e.nextElement();
            if(temp.isDirty()==null){
                PageId ids=temp.getId();
                pool.remove(ids);
                return;
            }
        }
        throw new DbException("Full Bufferpool");
    }
        

}
