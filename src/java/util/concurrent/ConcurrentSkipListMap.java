/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * This file is available under and governed by the GNU General Public
 * License version 2 only, as published by the Free Software Foundation.
 * However, the following notice accompanied the original version of this
 * file:
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;

import java.io.IOException;
import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.SortedMap;
import java.util.Spliterator;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A scalable concurrent {@link ConcurrentNavigableMap} implementation.
 * The map is sorted according to the {@linkplain Comparable natural
 * ordering} of its keys, or by a {@link Comparator} provided at map
 * creation time, depending on which constructor is used.
 *
 * <p>This class implements a concurrent variant of <a
 * href="http://en.wikipedia.org/wiki/Skip_list" target="_top">SkipLists</a>
 * providing expected average <i>log(n)</i> time cost for the
 * {@code containsKey}, {@code get}, {@code put} and
 * {@code remove} operations and their variants.  Insertion, removal,
 * update, and access operations safely execute concurrently by
 * multiple threads.
 *
 * <p>Iterators and spliterators are
 * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
 *
 * <p>Ascending key ordered views and their iterators are faster than
 * descending ones.
 *
 * <p>All {@code Map.Entry} pairs returned by methods in this class
 * and its views represent snapshots of mappings at the time they were
 * produced. They do <em>not</em> support the {@code Entry.setValue}
 * method. (Note however that it is possible to change mappings in the
 * associated map using {@code put}, {@code putIfAbsent}, or
 * {@code replace}, depending on exactly which effect you need.)
 *
 * <p>Beware that bulk operations {@code putAll}, {@code equals},
 * {@code toArray}, {@code containsValue}, and {@code clear} are
 * <em>not</em> guaranteed to be performed atomically. For example, an
 * iterator operating concurrently with a {@code putAll} operation
 * might view only some of the added elements.
 *
 * <p>This class and its views and iterators implement all of the
 * <em>optional</em> methods of the {@link Map} and {@link Iterator}
 * interfaces. Like most other concurrent collections, this class does
 * <em>not</em> permit the use of {@code null} keys or values because some
 * null return values cannot be reliably distinguished from the absence of
 * elements.
 *
 * <p>This class is a member of the
 * <a href="{@docRoot}/java.base/java/util/package-summary.html#CollectionsFramework">
 * Java Collections Framework</a>.
 *
 * @author Doug Lea
 * @param <K> the type of keys maintained by this map
 * @param <V> the type of mapped values
 * @since 1.6
 */

/*
 * ??????-????????????????????????????????????????????????key???value????????????null
 *
 * ??????????????????????????????????????????????????????????????????????????????????????????????????????comparator
 * ???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
 *
 * ConcurrentSkipListMap??????????????????Map????????????????????????????????????HashMap??????ConcurrentHashMap
 * ???????????????????????????????????????????????????ConcurrentSkipListMap
 */
public class ConcurrentSkipListMap<K, V> extends AbstractMap<K, V> implements ConcurrentNavigableMap<K, V>, Cloneable, Serializable {
    
    /*
     * This class implements a tree-like two-dimensionally linked skip
     * list in which the index levels are represented in separate
     * nodes from the base nodes holding data.  There are two reasons
     * for taking this approach instead of the usual array-based
     * structure: 1) Array based implementations seem to encounter
     * more complexity and overhead 2) We can use cheaper algorithms
     * for the heavily-traversed index lists than can be used for the
     * base lists.  Here's a picture of some of the basics for a
     * possible list with 2 levels of index:
     *
     * Head nodes          Index nodes
     * +-+    right        +-+                      +-+
     * |2|---------------->| |--------------------->| |->null
     * +-+                 +-+                      +-+
     *  | down              |                        |
     *  v                   v                        v
     * +-+            +-+  +-+       +-+            +-+       +-+
     * |1|----------->| |->| |------>| |----------->| |------>| |->null
     * +-+            +-+  +-+       +-+            +-+       +-+
     *  v              |    |         |              |         |
     * Nodes  next     v    v         v              v         v
     * +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+
     * | |->|A|->|B|->|C|->|D|->|E|->|F|->|G|->|H|->|I|->|J|->|K|->null
     * +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+
     *
     * The base lists use a variant of the HM linked ordered set
     * algorithm. See Tim Harris, "A pragmatic implementation of
     * non-blocking linked lists"
     * http://www.cl.cam.ac.uk/~tlh20/publications.html and Maged
     * Michael "High Performance Dynamic Lock-Free Hash Tables and
     * List-Based Sets"
     * http://www.research.ibm.com/people/m/michael/pubs.htm.  The
     * basic idea in these lists is to mark the "next" pointers of
     * deleted nodes when deleting to avoid conflicts with concurrent
     * insertions, and when traversing to keep track of triples
     * (predecessor, node, successor) in order to detect when and how
     * to unlink these deleted nodes.
     *
     * Rather than using mark-bits to mark list deletions (which can
     * be slow and space-intensive using AtomicMarkedReference), nodes
     * use direct CAS'able next pointers.  On deletion, instead of
     * marking a pointer, they splice in another node that can be
     * thought of as standing for a marked pointer (see method
     * unlinkNode).  Using plain nodes acts roughly like "boxed"
     * implementations of marked pointers, but uses new nodes only
     * when nodes are deleted, not for every link.  This requires less
     * space and supports faster traversal. Even if marked references
     * were better supported by JVMs, traversal using this technique
     * might still be faster because any search need only read ahead
     * one more node than otherwise required (to check for trailing
     * marker) rather than unmasking mark bits or whatever on each
     * read.
     *
     * This approach maintains the essential property needed in the HM
     * algorithm of changing the next-pointer of a deleted node so
     * that any other CAS of it will fail, but implements the idea by
     * changing the pointer to point to a different node (with
     * otherwise illegal null fields), not by marking it.  While it
     * would be possible to further squeeze space by defining marker
     * nodes not to have key/value fields, it isn't worth the extra
     * type-testing overhead.  The deletion markers are rarely
     * encountered during traversal, are easily detected via null
     * checks that are needed anyway, and are normally quickly garbage
     * collected. (Note that this technique would not work well in
     * systems without garbage collection.)
     *
     * In addition to using deletion markers, the lists also use
     * nullness of value fields to indicate deletion, in a style
     * similar to typical lazy-deletion schemes.  If a node's value is
     * null, then it is considered logically deleted and ignored even
     * though it is still reachable.
     *
     * Here's the sequence of events for a deletion of node n with
     * predecessor b and successor f, initially:
     *
     *        +------+       +------+      +------+
     *   ...  |   b  |------>|   n  |----->|   f  | ...
     *        +------+       +------+      +------+
     *
     * 1. CAS n's value field from non-null to null.
     *    Traversals encountering a node with null value ignore it.
     *    However, ongoing insertions and deletions might still modify
     *    n's next pointer.
     *
     * 2. CAS n's next pointer to point to a new marker node.
     *    From this point on, no other nodes can be appended to n.
     *    which avoids deletion errors in CAS-based linked lists.
     *
     *        +------+       +------+      +------+       +------+
     *   ...  |   b  |------>|   n  |----->|marker|------>|   f  | ...
     *        +------+       +------+      +------+       +------+
     *
     * 3. CAS b's next pointer over both n and its marker.
     *    From this point on, no new traversals will encounter n,
     *    and it can eventually be GCed.
     *        +------+                                    +------+
     *   ...  |   b  |----------------------------------->|   f  | ...
     *        +------+                                    +------+
     *
     * A failure at step 1 leads to simple retry due to a lost race
     * with another operation. Steps 2-3 can fail because some other
     * thread noticed during a traversal a node with null value and
     * helped out by marking and/or unlinking.  This helping-out
     * ensures that no thread can become stuck waiting for progress of
     * the deleting thread.
     *
     * Skip lists add indexing to this scheme, so that the base-level
     * traversals start close to the locations being found, inserted
     * or deleted -- usually base level traversals only traverse a few
     * nodes. This doesn't change the basic algorithm except for the
     * need to make sure base traversals start at predecessors (here,
     * b) that are not (structurally) deleted, otherwise retrying
     * after processing the deletion.
     *
     * Index levels are maintained using CAS to link and unlink
     * successors ("right" fields).  Races are allowed in index-list
     * operations that can (rarely) fail to link in a new index node.
     * (We can't do this of course for data nodes.)  However, even
     * when this happens, the index lists correctly guide search.
     * This can impact performance, but since skip lists are
     * probabilistic anyway, the net result is that under contention,
     * the effective "p" value may be lower than its nominal value.
     *
     * Index insertion and deletion sometimes require a separate
     * traversal pass occurring after the base-level action, to add or
     * remove index nodes.  This adds to single-threaded overhead, but
     * improves contended multithreaded performance by narrowing
     * interference windows, and allows deletion to ensure that all
     * index nodes will be made unreachable upon return from a public
     * remove operation, thus avoiding unwanted garbage retention.
     *
     * Indexing uses skip list parameters that maintain good search
     * performance while using sparser-than-usual indices: The
     * hardwired parameters k=1, p=0.5 (see method doPut) mean that
     * about one-quarter of the nodes have indices. Of those that do,
     * half have one level, a quarter have two, and so on (see Pugh's
     * Skip List Cookbook, sec 3.4), up to a maximum of 62 levels
     * (appropriate for up to 2^63 elements).  The expected total
     * space requirement for a map is slightly less than for the
     * current implementation of java.util.TreeMap.
     *
     * Changing the level of the index (i.e, the height of the
     * tree-like structure) also uses CAS.  Creation of an index with
     * height greater than the current level adds a level to the head
     * index by CAS'ing on a new top-most head. To maintain good
     * performance after a lot of removals, deletion methods
     * heuristically try to reduce the height if the topmost levels
     * appear to be empty.  This may encounter races in which it is
     * possible (but rare) to reduce and "lose" a level just as it is
     * about to contain an index (that will then never be
     * encountered). This does no structural harm, and in practice
     * appears to be a better option than allowing unrestrained growth
     * of levels.
     *
     * This class provides concurrent-reader-style memory consistency,
     * ensuring that read-only methods report status and/or values no
     * staler than those holding at method entry. This is done by
     * performing all publication and structural updates using
     * (volatile) CAS, placing an acquireFence in a few access
     * methods, and ensuring that linked objects are transitively
     * acquired via dependent reads (normally once) unless performing
     * a volatile-mode CAS operation (that also acts as an acquire and
     * release).  This form of fence-hoisting is similar to RCU and
     * related techniques (see McKenney's online book
     * https://www.kernel.org/pub/linux/kernel/people/paulmck/perfbook/perfbook.html)
     * It minimizes overhead that may otherwise occur when using so
     * many volatile-mode reads. Using explicit acquireFences is
     * logistically easier than targeting particular fields to be read
     * in acquire mode: fences are just hoisted up as far as possible,
     * to the entry points or loop headers of a few methods. A
     * potential disadvantage is that these few remaining fences are
     * not easily optimized away by compilers under exclusively
     * single-thread use.  It requires some care to avoid volatile
     * mode reads of other fields. (Note that the memory semantics of
     * a reference dependently read in plain mode exactly once are
     * equivalent to those for atomic opaque mode.)  Iterators and
     * other traversals encounter each node and value exactly once.
     * Other operations locate an element (or position to insert an
     * element) via a sequence of dereferences. This search is broken
     * into two parts. Method findPredecessor (and its specialized
     * embeddings) searches index nodes only, returning a base-level
     * predecessor of the key. Callers carry out the base-level
     * search, restarting if encountering a marker preventing link
     * modification.  In some cases, it is possible to encounter a
     * node multiple times while descending levels. For mutative
     * operations, the reported value is validated using CAS (else
     * retrying), preserving linearizability with respect to each
     * other. Others may return any (non-null) value holding in the
     * course of the method call.  (Search-based methods also include
     * some useless-looking explicit null checks designed to allow
     * more fields to be nulled out upon removal, to reduce floating
     * garbage, but which is not currently done, pending discovery of
     * a way to do this with less impact on other operations.)
     *
     * To produce random values without interference across threads,
     * we use within-JDK thread local random support (via the
     * "secondary seed", to avoid interference with user-level
     * ThreadLocalRandom.)
     *
     * For explanation of algorithms sharing at least a couple of
     * features with this one, see Mikhail Fomitchev's thesis
     * (http://www.cs.yorku.ca/~mikhail/), Keir Fraser's thesis
     * (http://www.cl.cam.ac.uk/users/kaf24/), and Hakan Sundell's
     * thesis (http://www.cs.chalmers.se/~phs/).
     *
     * Notation guide for local variables
     * Node:         b, n, f, p for  predecessor, node, successor, aux
     * Index:        q, r, d    for index node, right, down.
     * Head:         h
     * Keys:         k, key
     * Values:       v, value
     * Comparisons:  c
     */
    
    
    private static final int EQ = 1;
    private static final int LT = 2;
    private static final int GT = 0; // Actually checked as !LT
    
    
    /**
     * The comparator used to maintain order in this map, or null if
     * using natural ordering.  (Non-private to simplify access in
     * nested classes.)
     * @serial
     */
    final Comparator<? super K> comparator; // ???????????????
    
    /** Lazily initialized topmost index of the skiplist. */
    private transient Index<K,V> head;  // ??????????????????????????????????????????????????????
    
    /** Lazily initialized element count */
    private transient LongAdder adder;  // ????????????
    
    
    /** Lazily initialized key set */
    private transient KeySet<K,V> keySet;
    /** Lazily initialized values collection */
    private transient Values<K,V> values;
    /** Lazily initialized entry set */
    private transient EntrySet<K,V> entrySet;
    
    
    /** Lazily initialized descending map */
    private transient SubMap<K,V> descendingMap;    // ????????????Map
    
    
    // VarHandle mechanics
    private static final VarHandle HEAD;
    private static final VarHandle ADDER;
    private static final VarHandle NEXT;
    private static final VarHandle VAL;
    private static final VarHandle RIGHT;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            HEAD = l.findVarHandle(ConcurrentSkipListMap.class, "head", Index.class);
            ADDER = l.findVarHandle(ConcurrentSkipListMap.class, "adder", LongAdder.class);
            NEXT = l.findVarHandle(Node.class, "next", Node.class);
            VAL = l.findVarHandle(Node.class, "val", Object.class);
            RIGHT = l.findVarHandle(Index.class, "right", Index.class);
        } catch(ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    
    
    
    /*??? ????????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? */
    
    /**
     * Constructs a new, empty map, sorted according to the
     * {@linkplain Comparable natural ordering} of the keys.
     */
    public ConcurrentSkipListMap() {
        this.comparator = null;
    }
    
    /**
     * Constructs a new, empty map, sorted according to the specified
     * comparator.
     *
     * @param comparator the comparator that will be used to order this map.
     *        If {@code null}, the {@linkplain Comparable natural
     *        ordering} of the keys will be used.
     */
    public ConcurrentSkipListMap(Comparator<? super K> comparator) {
        this.comparator = comparator;
    }
    
    /**
     * Constructs a new map containing the same mappings as the given map,
     * sorted according to the {@linkplain Comparable natural ordering} of
     * the keys.
     *
     * @param  m the map whose mappings are to be placed in this map
     * @throws ClassCastException if the keys in {@code m} are not
     *         {@link Comparable}, or are not mutually comparable
     * @throws NullPointerException if the specified map or any of its keys
     *         or values are null
     */
    public ConcurrentSkipListMap(Map<? extends K, ? extends V> m) {
        this.comparator = null;
        putAll(m);
    }
    
    /**
     * Constructs a new map containing the same mappings and using the
     * same ordering as the specified sorted map.
     *
     * @param m the sorted map whose mappings are to be placed in this
     *        map, and whose comparator is to be used to sort this map
     * @throws NullPointerException if the specified sorted map or any of
     *         its keys or values are null
     */
    public ConcurrentSkipListMap(SortedMap<K, ? extends V> m) {
        this.comparator = m.comparator();
        buildFromSorted(m); // initializes transients
    }
    
    /*??? ????????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? */
    
    
    
    /*??? ?????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? */
    
    /**
     * Associates the specified value with the specified key in this map.
     * If the map previously contained a mapping for the key, the old
     * value is replaced.
     *
     * @param key   key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     *
     * @return the previous value associated with the specified key, or
     * {@code null} if there was no mapping for the key
     *
     * @throws ClassCastException   if the specified key cannot be compared
     *                              with the keys currently in the map
     * @throws NullPointerException if the specified key or value is null
     */
    // ?????????????????????key-value?????????Map?????????????????????????????????
    public V put(K key, V value) {
        if(value == null) {
            throw new NullPointerException();
        }
        
        return doPut(key, value, false);
    }
    
    /**
     * {@inheritDoc}
     *
     * @return the previous value associated with the specified key,
     * or {@code null} if there was no mapping for the key
     *
     * @throws ClassCastException   if the specified key cannot be compared
     *                              with the keys currently in the map
     * @throws NullPointerException if the specified key or value is null
     */
    // ?????????????????????key-value?????????Map????????????????????????????????????
    public V putIfAbsent(K key, V value) {
        if(value == null) {
            throw new NullPointerException();
        }
        
        return doPut(key, value, true);
    }
    
    /*??? ?????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? */
    
    
    
    /*??? ?????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? */
    
    /**
     * Returns the value to which the specified key is mapped,
     * or {@code null} if this map contains no mapping for the key.
     *
     * <p>More formally, if this map contains a mapping from a key
     * {@code k} to a value {@code v} such that {@code key} compares
     * equal to {@code k} according to the map's ordering, then this
     * method returns {@code v}; otherwise it returns {@code null}.
     * (There can be at most one such mapping.)
     *
     * @throws ClassCastException   if the specified key cannot be compared
     *                              with the keys currently in the map
     * @throws NullPointerException if the specified key is null
     */
    // ???????????????key???????????????value??????????????????????????????null
    public V get(Object key) {
        return doGet(key);
    }
    
    /**
     * Returns the value to which the specified key is mapped,
     * or the given defaultValue if this map contains no mapping for the key.
     *
     * @param key          the key
     * @param defaultValue the value to return if this map contains
     *                     no mapping for the given key
     *
     * @return the mapping for the key, if present; else the defaultValue
     *
     * @throws NullPointerException if the specified key is null
     * @since 1.8
     */
    // ???????????????key???????????????value????????????????????????????????????????????????defaultValue
    public V getOrDefault(Object key, V defaultValue) {
        V v = doGet(key);
        return v != null ? v : defaultValue;
    }
    
    /*??? ?????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? */
    
    
    
    /*??? ?????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? */
    
    /**
     * Removes the mapping for the specified key from this map if present.
     *
     * @param key key for which mapping should be removed
     *
     * @return the previous value associated with the specified key, or
     * {@code null} if there was no mapping for the key
     *
     * @throws ClassCastException   if the specified key cannot be compared
     *                              with the keys currently in the map
     * @throws NullPointerException if the specified key is null
     */
    // ??????????????????key????????????????????????????????????????????????
    public V remove(Object key) {
        return doRemove(key, null);
    }
    
    /**
     * {@inheritDoc}
     *
     * @throws ClassCastException   if the specified key cannot be compared
     *                              with the keys currently in the map
     * @throws NullPointerException if the specified key is null
     */
    // ??????????????????key???value?????????????????????????????????????????????
    public boolean remove(Object key, Object value) {
        if(key == null) {
            throw new NullPointerException();
        }
        
        return value != null && doRemove(key, value) != null;
    }
    
    
    /**
     * Removes all of the mappings from this map.
     */
    // ????????????Map???????????????
    public void clear() {
        Index<K, V> h, r, d;
        
        VarHandle.acquireFence();
        
        while((h = head) != null) {
            // remove indices
            if((r = h.right) != null) {
                RIGHT.compareAndSet(h, r, null);
                
                // remove levels
            } else if((d = h.down) != null) {
                HEAD.compareAndSet(this, h, d);
                
                // ???????????????????????????????????????
            } else {
                long count = 0L;
                Node<K, V> b = h.node;
                
                // remove nodes
                if(b != null) {
                    Node<K, V> n;
                    
                    while((n = b.next) != null) {
                        V v = n.val;
                        
                        if(v != null && VAL.compareAndSet(n, v, null)) {
                            --count;
                            v = null;
                        }
                        
                        if(v == null) {
                            unlinkNode(b, n);
                        }
                    }
                }
                
                if(count != 0L) {
                    addCount(count);
                } else {
                    break;
                }
            }
        }
    }
    
    /*??? ?????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? */
    
    
    
    /*??? ?????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? */
    
    /**
     * {@inheritDoc}
     *
     * @return the previous value associated with the specified key,
     * or {@code null} if there was no mapping for the key
     *
     * @throws ClassCastException   if the specified key cannot be compared
     *                              with the keys currently in the map
     * @throws NullPointerException if the specified key or value is null
     */
    // ???????????????key????????????????????????newValue????????????????????????????????????????????????????????????null???
    public V replace(K key, V newValue) {
        if(key == null || newValue == null) {
            throw new NullPointerException();
        }
        
        for(; ; ) {
            // ????????????key????????????????????????????????????null
            Node<K, V> n = findNode(key);
            if(n== null) {
                return null;
            }
            
            V v = n.val;
            if(v != null && VAL.compareAndSet(n, v, newValue)) {
                return v;
            }
        }
    }
    
    /**
     * {@inheritDoc}
     *
     * @throws ClassCastException   if the specified key cannot be compared
     *                              with the keys currently in the map
     * @throws NullPointerException if any of the arguments are null
     */
    // ???????????????key???oldValue????????????????????????newValue????????????????????????????????????
    public boolean replace(K key, V oldValue, V newValue) {
        if(key == null || oldValue == null || newValue == null) {
            throw new NullPointerException();
        }
        
        for(; ; ) {
            Node<K, V> n = findNode(key);
            if(n == null) {
                return false;
            }
            
            V v = n.val;
            if(v != null) {
                if(!oldValue.equals(v)) {
                    return false;
                }
                
                if(VAL.compareAndSet(n, v, newValue)) {
                    return true;
                }
            }
        }
    }
    
    // ????????????Map????????????????????????????????????function?????????function?????????????????????key???value?????????????????????
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        if(function == null) {
            throw new NullPointerException();
        }
        
        // ????????????(??????)??????????????????
        Node<K, V> b = baseHead();
        if(b == null) {
            return;
        }
        
        Node<K, V> n;
        
        while((n = b.next) != null) {
            V v;
            
            while((v = n.val) != null) {
                V r = function.apply(n.key, v);
                
                if(r == null) {
                    throw new NullPointerException();
                }
                
                if(VAL.compareAndSet(n, v, r)) {
                    break;
                }
            }
            
            b = n;
        }
    }
    
    /*??? ?????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? */
    
    
    
    /*??? ???????????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? */
    
    /**
     * Returns {@code true} if this map contains a mapping for the specified
     * key.
     *
     * @param key key whose presence in this map is to be tested
     *
     * @return {@code true} if this map contains a mapping for the specified key
     *
     * @throws ClassCastException   if the specified key cannot be compared
     *                              with the keys currently in the map
     * @throws NullPointerException if the specified key is null
     */
    // ??????Map?????????????????????key?????????
    public boolean containsKey(Object key) {
        return doGet(key) != null;
    }
    
    /**
     * Returns {@code true} if this map maps one or more keys to the
     * specified value.  This operation requires time linear in the
     * map size. Additionally, it is possible for the map to change
     * during execution of this method, in which case the returned
     * result may be inaccurate.
     *
     * @param value value whose presence in this map is to be tested
     *
     * @return {@code true} if a mapping to {@code value} exists;
     * {@code false} otherwise
     *
     * @throws NullPointerException if the specified value is null
     */
    // ??????Map?????????????????????value?????????
    public boolean containsValue(Object value) {
        if(value == null) {
            throw new NullPointerException();
        }
        
        // ????????????(??????)??????????????????
        Node<K, V> b = baseHead();
        if(b == null) {
            return false;
        }
        
        Node<K, V> n;
        
        // ????????????????????????
        while((n = b.next) != null) {
            V v = n.val;
            if(v != null && value.equals(v)) {
                return true;
            } else {
                b = n;
            }
        }
        
        return false;
    }
    
    /*??? ???????????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? */
    
    
    
    /*??? ?????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? */
    
    /*
     * Note: Lazy initialization works for views because view classes
     * are stateless/immutable so it doesn't matter wrt correctness if
     * more than one is created (which will only rarely happen).  Even
     * so, the following idiom conservatively ensures that the method
     * returns the one it created if it does so, not one created by
     * another racing thread.
     */
    
    /**
     * Returns a {@link NavigableSet} view of the keys contained in this map.
     *
     * <p>The set's iterator returns the keys in ascending order.
     * The set's spliterator additionally reports {@link Spliterator#CONCURRENT},
     * {@link Spliterator#NONNULL}, {@link Spliterator#SORTED} and
     * {@link Spliterator#ORDERED}, with an encounter order that is ascending
     * key order.
     *
     * <p>The {@linkplain Spliterator#getComparator() spliterator's comparator}
     * is {@code null} if the {@linkplain #comparator() map's comparator}
     * is {@code null}.
     * Otherwise, the spliterator's comparator is the same as or imposes the
     * same total ordering as the map's comparator.
     *
     * <p>The set is backed by the map, so changes to the map are
     * reflected in the set, and vice-versa.  The set supports element
     * removal, which removes the corresponding mapping from the map,
     * via the {@code Iterator.remove}, {@code Set.remove},
     * {@code removeAll}, {@code retainAll}, and {@code clear}
     * operations.  It does not support the {@code add} or {@code addAll}
     * operations.
     *
     * <p>The view's iterators and spliterators are
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * <p>This method is equivalent to method {@code navigableKeySet}.
     *
     * @return a navigable set view of the keys in this map
     */
    // ??????Map???key?????????
    public NavigableSet<K> keySet() {
        KeySet<K, V> ks = keySet;
        if(ks != null) {
            return ks;
        }
        
        return keySet = new KeySet<>(this);
    }
    
    /**
     * Returns a {@link Collection} view of the values contained in this map.
     * <p>The collection's iterator returns the values in ascending order
     * of the corresponding keys. The collections's spliterator additionally
     * reports {@link Spliterator#CONCURRENT}, {@link Spliterator#NONNULL} and
     * {@link Spliterator#ORDERED}, with an encounter order that is ascending
     * order of the corresponding keys.
     *
     * <p>The collection is backed by the map, so changes to the map are
     * reflected in the collection, and vice-versa.  The collection
     * supports element removal, which removes the corresponding
     * mapping from the map, via the {@code Iterator.remove},
     * {@code Collection.remove}, {@code removeAll},
     * {@code retainAll} and {@code clear} operations.  It does not
     * support the {@code add} or {@code addAll} operations.
     *
     * <p>The view's iterators and spliterators are
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     */
    // ??????Map???value?????????
    public Collection<V> values() {
        Values<K, V> vs = values;
        
        if(vs != null) {
            return vs;
        }
        
        return values = new Values<>(this);
    }
    
    /**
     * Returns a {@link Set} view of the mappings contained in this map.
     *
     * <p>The set's iterator returns the entries in ascending key order.  The
     * set's spliterator additionally reports {@link Spliterator#CONCURRENT},
     * {@link Spliterator#NONNULL}, {@link Spliterator#SORTED} and
     * {@link Spliterator#ORDERED}, with an encounter order that is ascending
     * key order.
     *
     * <p>The set is backed by the map, so changes to the map are
     * reflected in the set, and vice-versa.  The set supports element
     * removal, which removes the corresponding mapping from the map,
     * via the {@code Iterator.remove}, {@code Set.remove},
     * {@code removeAll}, {@code retainAll} and {@code clear}
     * operations.  It does not support the {@code add} or
     * {@code addAll} operations.
     *
     * <p>The view's iterators and spliterators are
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * <p>The {@code Map.Entry} elements traversed by the {@code iterator}
     * or {@code spliterator} do <em>not</em> support the {@code setValue}
     * operation.
     *
     * @return a set view of the mappings contained in this map,
     * sorted in ascending key order
     */
    // ??????Map???key-value????????????
    public Set<Map.Entry<K, V>> entrySet() {
        EntrySet<K, V> es = entrySet;
        
        if(es != null) {
            return es;
        }
        
        return entrySet = new EntrySet<K, V>(this);
    }
    
    /*??? ?????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? */
    
    
    
    /*??? ?????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? */
    
    // ????????????Map??????????????????????????????action?????????action?????????????????????key???value
    public void forEach(BiConsumer<? super K, ? super V> action) {
        if(action == null) {
            throw new NullPointerException();
        }
        
        // ????????????(??????)??????????????????
        Node<K, V> b = baseHead();
        if(b == null) {
            return;
        }
        
        Node<K, V> n;
        while((n = b.next) != null) {
            V v = n.val;
            if(v != null) {
                action.accept(n.key, v);
            }
            
            b = n;
        }
    }
    
    /*??? ?????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? */
    
    
    
    /*??? ???????????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? */
    
    /**
     * If the specified key is not already associated with a value,
     * associates it with the given value.  Otherwise, replaces the
     * value with the results of the given remapping function, or
     * removes if {@code null}. The function is <em>NOT</em>
     * guaranteed to be applied once atomically.
     *
     * @param key               key with which the specified value is to be associated
     * @param value             the value to use if absent
     * @param remappingFunction the function to recompute a value if present
     *
     * @return the new value associated with the specified key, or null if none
     *
     * @throws NullPointerException if the specified key or value is null
     *                              or the remappingFunction is null
     * @since 1.8
     */
    // ??????/??????/??????????????????????????????????????????value??????value????????????value????????????value
    public V merge(K key, V bakValue, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        if(key == null || bakValue == null || remappingFunction == null) {
            throw new NullPointerException();
        }
        
        for(; ; ) {
            // ??????????????????key????????????????????????????????????null
            Node<K, V> n = findNode(key);
            
            V oldValue;
            
            // ???????????????????????????key?????????
            if(n == null) {
                // ???????????????
                if(doPut(key, bakValue, true) == null) {
                    return bakValue;
                }
            } else if((oldValue = n.val) != null) {
                // ????????????
                V newValue = remappingFunction.apply(oldValue, bakValue);
                
                // ??????????????????null
                if(newValue != null) {
                    // ????????????
                    if(VAL.compareAndSet(n, oldValue, newValue)) {
                        return newValue;
                    }
                    
                    // ?????????????????????????????????????????????
                } else if(doRemove(key, oldValue) != null) {
                    return null;
                }
            }
        }
    }
    
    /**
     * Attempts to compute a mapping for the specified key and its
     * current mapped value (or {@code null} if there is no current
     * mapping). The function is <em>NOT</em> guaranteed to be applied
     * once atomically.
     *
     * @param key               key with which the specified value is to be associated
     * @param remappingFunction the function to compute a value
     *
     * @return the new value associated with the specified key, or null if none
     *
     * @throws NullPointerException if the specified key is null
     *                              or the remappingFunction is null
     * @since 1.8
     */
    // ??????/??????/????????????????????????????????????key??????value????????????value????????????value
    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if(key == null || remappingFunction == null) {
            throw new NullPointerException();
        }
        
        for(; ; ) {
            // ??????????????????key????????????????????????????????????null
            Node<K, V> n = findNode(key);
            
            V oldValue;
            
            V newValue;
            
            // ?????????????????????key?????????
            if(n == null) {
                // ????????????
                newValue = remappingFunction.apply(key, null);
                
                if(newValue == null) {
                    break;
                }
                
                if(doPut(key, newValue, true) == null) {
                    return newValue;
                }
            } else if((oldValue = n.val) != null) {
                // ????????????
                newValue = remappingFunction.apply(key, oldValue);
                
                if(newValue != null) {
                    if(VAL.compareAndSet(n, oldValue, newValue)) {
                        return newValue;
                    }
                } else if(doRemove(key, oldValue) != null) {
                    break;
                }
            }
        }
        
        return null;
    }
    
    /**
     * If the value for the specified key is present, attempts to
     * compute a new mapping given the key and its current mapped
     * value. The function is <em>NOT</em> guaranteed to be applied
     * once atomically.
     *
     * @param key               key with which a value may be associated
     * @param remappingFunction the function to compute a value
     *
     * @return the new value associated with the specified key, or null if none
     *
     * @throws NullPointerException if the specified key is null
     *                              or the remappingFunction is null
     * @since 1.8
     */
    // ??????/?????????????????????????????????????????????????????????value??????null????????????key??????value????????????value????????????value
    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if(key == null || remappingFunction == null) {
            throw new NullPointerException();
        }
        
        Node<K, V> n;
        
        // ????????????????????????key?????????
        while((n = findNode(key)) != null) {
            V oldValue = n.val;
            
            // ???????????????null?????????
            if(oldValue == null) {
                continue;
            }
            
            // ????????????
            V newValue = remappingFunction.apply(key, oldValue);
            
            // ??????????????????null
            if(newValue != null) {
                // ?????????????????????
                if(VAL.compareAndSet(n, oldValue, newValue)) {
                    return newValue;
                }
                
                // ???????????????null?????????????????????
            } else if(doRemove(key, oldValue) != null) {
                break;
            }
        }
        
        return null;
    }
    
    /**
     * If the specified key is not already associated with a value,
     * attempts to compute its value using the given mapping function
     * and enters it into this map unless {@code null}.  The function
     * is <em>NOT</em> guaranteed to be applied once atomically only
     * if the value is not present.
     *
     * @param key             key with which the specified value is to be associated
     * @param mappingFunction the function to compute a value
     *
     * @return the current (existing or computed) value associated with
     * the specified key, or null if the computed value is null
     *
     * @throws NullPointerException if the specified key is null
     *                              or the mappingFunction is null
     * @since 1.8
     */
    // ??????/????????????????????????????????????????????????????????????value???null????????????key????????????value????????????value
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        if(key == null || mappingFunction == null) {
            throw new NullPointerException();
        }
        
        V oldValue = doGet(key);
        
        // ????????????????????????key????????????????????????
        if(oldValue != null) {
            return oldValue;
        }
        
        // ????????????
        V newValue = mappingFunction.apply(key);
        
        // ???????????????null
        if(newValue == null) {
            return null;
        }
        
        // ???????????????????????????
        V p = doPut(key, newValue, true);
        
        return p == null ? newValue : p;
    }
    
    /*??? ???????????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? */
    
    
    
    /*??? ?????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? */
    
    /**
     * {@inheritDoc}
     */
    // ????????????Map??????????????????
    public int size() {
        long c;
        if(baseHead() == null) {
            return 0;
        } else {
            return ((c = getAdderCount()) >= Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) c;
        }
    }
    
    /**
     * {@inheritDoc}
     */
    // ????????????Map???????????????
    public boolean isEmpty() {
        return findFirst() == null;
    }
    
    /*??? ?????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? */
    
    
    
    /*??? ????????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? */
    
    private static final long serialVersionUID = -8627078645895051609L;
    
    
    /**
     * Saves this map to a stream (that is, serializes it).
     *
     * @param s the stream
     *
     * @throws java.io.IOException if an I/O error occurs
     * @serialData The key (Object) and value (Object) for each
     * key-value mapping represented by the map, followed by
     * {@code null}. The key-value mappings are emitted in key-order
     * (as determined by the Comparator, or by the keys' natural
     * ordering if no Comparator).
     */
    private void writeObject(java.io.ObjectOutputStream s) throws IOException {
        // Write out the Comparator and any hidden stuff
        s.defaultWriteObject();
        
        // Write out keys and values (alternating)
        Node<K, V> b, n;
        V v;
        if((b = baseHead()) != null) {
            while((n = b.next) != null) {
                if((v = n.val) != null) {
                    s.writeObject(n.key);
                    s.writeObject(v);
                }
                b = n;
            }
        }
        s.writeObject(null);
    }
    
    /**
     * Reconstitutes this map from a stream (that is, deserializes it).
     *
     * @param s the stream
     *
     * @throws ClassNotFoundException if the class of a serialized object
     *                                could not be found
     * @throws java.io.IOException    if an I/O error occurs
     */
    @SuppressWarnings("unchecked")
    private void readObject(final java.io.ObjectInputStream s) throws IOException, ClassNotFoundException {
        // Read in the Comparator and any hidden stuff
        s.defaultReadObject();
        
        // Same idea as buildFromSorted
        @SuppressWarnings("unchecked")
        Index<K, V>[] preds = (Index<K, V>[]) new Index<?, ?>[64];
        Node<K, V> bp = new Node<K, V>(null, null, null);
        Index<K, V> h = preds[0] = new Index<K, V>(bp, null, null);
        Comparator<? super K> cmp = comparator;
        K prevKey = null;
        long count = 0;
        
        for(; ; ) {
            K k = (K) s.readObject();
            if(k == null)
                break;
            V v = (V) s.readObject();
            if(v == null)
                throw new NullPointerException();
            if(prevKey != null && cpr(cmp, prevKey, k)>0)
                throw new IllegalStateException("out of order");
            prevKey = k;
            Node<K, V> z = new Node<K, V>(k, v, null);
            bp = bp.next = z;
            if((++count & 3L) == 0L) {
                long m = count >>> 2;
                int i = 0;
                Index<K, V> idx = null, q;
                do {
                    idx = new Index<K, V>(z, idx, null);
                    if((q = preds[i]) == null)
                        preds[i] = h = new Index<K, V>(h.node, h, idx);
                    else
                        preds[i] = q.right = idx;
                } while(++i<preds.length && ((m >>>= 1) & 1L) != 0L);
            }
        }
        if(count != 0L) {
            VarHandle.releaseFence();
            addCount(count);
            head = h;
            VarHandle.fullFence();
        }
    }
    
    /*??? ????????? ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? */
    
    
    
    /*??? NavigableMap/SortedMap ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? */
    
    // ????????????Map??????????????????Comparator
    public Comparator<? super K> comparator() {
        return comparator;
    }
    
    
    
    /**
     * @throws NoSuchElementException {@inheritDoc}
     */
    // ????????????(??????)??????????????????key
    public K firstKey() {
        // ????????????(??????)???????????????
        Node<K,V> n = findFirst();
        if (n == null) {
            throw new NoSuchElementException();
        }
        
        return n.key;
    }
    
    /**
     * @throws NoSuchElementException {@inheritDoc}
     */
    // ????????????(??????)????????????????????????key
    public K lastKey() {
        // ????????????(??????)?????????????????????
        Node<K,V> n = findLast();
        if (n == null) {
            throw new NoSuchElementException();
        }
        
        return n.key;
    }
    
    /**
     * @throws ClassCastException   {@inheritDoc}
     * @throws NullPointerException if the specified key is null
     */
    // ????????????????????????key??????key???????????????Map?????????key?????????
    public K lowerKey(K key) {
        Node<K, V> n = findNear(key, LT, comparator);
        
        return (n == null) ? null : n.key;
    }
    
    /**
     * @param key the key
     *
     * @throws ClassCastException   {@inheritDoc}
     * @throws NullPointerException if the specified key is null
     */
    // ????????????????????????key??????key???????????????Map?????????key?????????
    public K higherKey(K key) {
        Node<K, V> n = findNear(key, GT, comparator);
        
        return (n == null) ? null : n.key;
    }
    
    /**
     * @param key the key
     *
     * @throws ClassCastException   {@inheritDoc}
     * @throws NullPointerException if the specified key is null
     */
    // ????????????????????????key??????key???????????????Map?????????key??????????????????key?????????
    public K floorKey(K key) {
        Node<K, V> n = findNear(key, LT | EQ, comparator);
        
        return (n == null) ? null : n.key;
    }
    
    /**
     * @throws ClassCastException   {@inheritDoc}
     * @throws NullPointerException if the specified key is null
     */
    // ????????????????????????key??????key???????????????Map?????????key??????????????????key?????????
    public K ceilingKey(K key) {
        Node<K, V> n = findNear(key, GT | EQ, comparator);
        
        return (n == null) ? null : n.key;
    }
    
    
    
    /**
     * Returns a key-value mapping associated with the least
     * key in this map, or {@code null} if the map is empty.
     * The returned entry does <em>not</em> support
     * the {@code Entry.setValue} method.
     */
    // ??????????????????Map?????????????????????????????????????????????????????????Entry
    public Map.Entry<K, V> firstEntry() {
        return findFirstEntry();
    }
    
    /**
     * Returns a key-value mapping associated with the greatest
     * key in this map, or {@code null} if the map is empty.
     * The returned entry does <em>not</em> support
     * the {@code Entry.setValue} method.
     */
    // ??????????????????Map???????????????????????????????????????????????????????????????Entry
    public Map.Entry<K, V> lastEntry() {
        return findLastEntry();
    }
    
    /**
     * Returns a key-value mapping associated with the greatest key
     * strictly less than the given key, or {@code null} if there is
     * no such key. The returned entry does <em>not</em> support the
     * {@code Entry.setValue} method.
     *
     * @throws ClassCastException   {@inheritDoc}
     * @throws NullPointerException if the specified key is null
     */
    // ???????????????????????????????????????????????????????????????Entry?????????????????????key??????????????????Map?????????key?????????
    public Map.Entry<K, V> lowerEntry(K key) {
        return findNearEntry(key, LT, comparator);
    }
    
    /**
     * Returns a key-value mapping associated with the least key
     * strictly greater than the given key, or {@code null} if there
     * is no such key. The returned entry does <em>not</em> support
     * the {@code Entry.setValue} method.
     *
     * @param key the key
     *
     * @throws ClassCastException   {@inheritDoc}
     * @throws NullPointerException if the specified key is null
     */
    // ???????????????????????????????????????????????????????????????Entry?????????????????????key??????????????????Map?????????key?????????
    public Map.Entry<K, V> higherEntry(K key) {
        return findNearEntry(key, GT, comparator);
    }
    
    /**
     * Returns a key-value mapping associated with the greatest key
     * less than or equal to the given key, or {@code null} if there
     * is no such key. The returned entry does <em>not</em> support
     * the {@code Entry.setValue} method.
     *
     * @param key the key
     *
     * @throws ClassCastException   {@inheritDoc}
     * @throws NullPointerException if the specified key is null
     */
    // ???????????????????????????????????????????????????????????????Entry?????????????????????key??????????????????Map?????????key??????????????????key?????????
    public Map.Entry<K, V> floorEntry(K key) {
        return findNearEntry(key, LT | EQ, comparator);
    }
    
    /**
     * Returns a key-value mapping associated with the least key
     * greater than or equal to the given key, or {@code null} if
     * there is no such entry. The returned entry does <em>not</em>
     * support the {@code Entry.setValue} method.
     *
     * @throws ClassCastException   {@inheritDoc}
     * @throws NullPointerException if the specified key is null
     */
    // ???????????????????????????????????????????????????????????????Entry?????????????????????key??????????????????Map?????????key??????????????????key?????????
    public Map.Entry<K, V> ceilingEntry(K key) {
        return findNearEntry(key, GT | EQ, comparator);
    }
    
    
    
    /**
     * Removes and returns a key-value mapping associated with
     * the least key in this map, or {@code null} if the map is empty.
     * The returned entry does <em>not</em> support
     * the {@code Entry.setValue} method.
     */
    // ??????????????????Map??????????????????????????????????????????????????????Entry?????????
    public Map.Entry<K, V> pollFirstEntry() {
        return doRemoveFirstEntry();
    }
    
    /**
     * Removes and returns a key-value mapping associated with
     * the greatest key in this map, or {@code null} if the map is empty.
     * The returned entry does <em>not</em> support
     * the {@code Entry.setValue} method.
     */
    // ??????????????????Map????????????????????????????????????????????????????????????Entry?????????
    public Map.Entry<K, V> pollLastEntry() {
        return doRemoveLastEntry();
    }
    
    
    
    // ????????????Map??????key?????????
    public NavigableSet<K> navigableKeySet() {
        KeySet<K, V> ks = keySet;
        if(ks != null) {
            return ks;
        }
        
        return keySet = new KeySet<>(this);
    }
    
    // ??????????????????Map??????key?????????
    public NavigableSet<K> descendingKeySet() {
        return descendingMap().navigableKeySet();
    }
    
    
    
    /**
     * @throws ClassCastException       {@inheritDoc}
     * @throws NullPointerException     if {@code fromKey} or {@code toKey} is null
     * @throws IllegalArgumentException {@inheritDoc}
     */
    // ??????[fromKey, toKey)????????????Map
    public ConcurrentNavigableMap<K, V> subMap(K fromKey, K toKey) {
        return subMap(fromKey, true, toKey, false);
    }
    
    /**
     * @throws ClassCastException       {@inheritDoc}
     * @throws NullPointerException     if {@code fromKey} or {@code toKey} is null
     * @throws IllegalArgumentException {@inheritDoc}
     */
    // ?????????fromKey, toKey???????????????Map????????????????????????fromInclusive???toInclusive????????????
    public ConcurrentNavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
        if(fromKey == null || toKey == null) {
            throw new NullPointerException();
        }
        return new SubMap<K, V>(this, fromKey, fromInclusive, toKey, toInclusive, false);
    }
    
    /**
     * @throws ClassCastException       {@inheritDoc}
     * @throws NullPointerException     if {@code toKey} is null
     * @throws IllegalArgumentException {@inheritDoc}
     */
    // ?????????<fromStart>, toKey)????????????Map
    public ConcurrentNavigableMap<K, V> headMap(K toKey) {
        return headMap(toKey, false);
    }
    
    /**
     * @throws ClassCastException       {@inheritDoc}
     * @throws NullPointerException     if {@code toKey} is null
     * @throws IllegalArgumentException {@inheritDoc}
     */
    // ??????[fromKey, <toEnd>???????????????Map
    public ConcurrentNavigableMap<K, V> headMap(K toKey, boolean inclusive) {
        if(toKey == null) {
            throw new NullPointerException();
        }
        return new SubMap<K, V>(this, null, false, toKey, inclusive, false);
    }
    
    /**
     * @throws ClassCastException       {@inheritDoc}
     * @throws NullPointerException     if {@code fromKey} is null
     * @throws IllegalArgumentException {@inheritDoc}
     */
    // ?????????fromKey, <toEnd>???????????????Map????????????????????????????????????inclusive????????????
    public ConcurrentNavigableMap<K, V> tailMap(K fromKey, boolean inclusive) {
        if(fromKey == null) {
            throw new NullPointerException();
        }
        return new SubMap<K, V>(this, fromKey, inclusive, null, false, false);
    }
    
    /**
     * @throws ClassCastException       {@inheritDoc}
     * @throws NullPointerException     if {@code fromKey} is null
     * @throws IllegalArgumentException {@inheritDoc}
     */
    // ??????[fromKey, <toEnd>???????????????Map
    public ConcurrentNavigableMap<K, V> tailMap(K fromKey) {
        return tailMap(fromKey, true);
    }
    
    
    
    // ??????????????????Map
    public ConcurrentNavigableMap<K, V> descendingMap() {
        ConcurrentNavigableMap<K, V> dm = descendingMap;
        if(dm != null) {
            return dm;
        }
        
        return descendingMap = new SubMap<K, V>(this, null, false, null, false, true);
    }
    
    /*??? NavigableMap/SortedMap ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????? */
    
    
    
    /**
     * Compares the specified object with this map for equality.
     * Returns {@code true} if the given object is also a map and the
     * two maps represent the same mappings.  More formally, two maps
     * {@code m1} and {@code m2} represent the same mappings if
     * {@code m1.entrySet().equals(m2.entrySet())}.  This
     * operation may return misleading results if either map is
     * concurrently modified during execution of this method.
     *
     * @param o object to be compared for equality with this map
     *
     * @return {@code true} if the specified object is equal to this map
     */
    public boolean equals(Object o) {
        if(o == this)
            return true;
        if(!(o instanceof Map))
            return false;
        Map<?, ?> m = (Map<?, ?>) o;
        try {
            Comparator<? super K> cmp = comparator;
            Iterator<? extends Map.Entry<?, ?>> it = m.entrySet().iterator();
            if(m instanceof SortedMap && ((SortedMap<?, ?>) m).comparator() == cmp) {
                Node<K, V> b, n;
                if((b = baseHead()) != null) {
                    while((n = b.next) != null) {
                        K k;
                        V v;
                        if((v = n.val) != null && (k = n.key) != null) {
                            if(!it.hasNext())
                                return false;
                            Map.Entry<?, ?> e = it.next();
                            Object mk = e.getKey();
                            Object mv = e.getValue();
                            if(mk == null || mv == null)
                                return false;
                            try {
                                if(cpr(cmp, k, mk) != 0)
                                    return false;
                            } catch(ClassCastException cce) {
                                return false;
                            }
                            if(!mv.equals(v))
                                return false;
                        }
                        b = n;
                    }
                }
                return !it.hasNext();
            } else {
                while(it.hasNext()) {
                    V v;
                    Map.Entry<?, ?> e = it.next();
                    Object mk = e.getKey();
                    Object mv = e.getValue();
                    if(mk == null || mv == null || (v = get(mk)) == null || !v.equals(mv))
                        return false;
                }
                Node<K, V> b, n;
                if((b = baseHead()) != null) {
                    K k;
                    V v;
                    Object mv;
                    while((n = b.next) != null) {
                        if((v = n.val) != null && (k = n.key) != null && ((mv = m.get(k)) == null || !mv.equals(v)))
                            return false;
                        b = n;
                    }
                }
                return true;
            }
        } catch(ClassCastException | NullPointerException unused) {
            return false;
        }
    }
    
    /**
     * Returns a shallow copy of this {@code ConcurrentSkipListMap}
     * instance. (The keys and values themselves are not cloned.)
     *
     * @return a shallow copy of this map
     */
    public ConcurrentSkipListMap<K, V> clone() {
        try {
            @SuppressWarnings("unchecked")
            ConcurrentSkipListMap<K, V> clone = (ConcurrentSkipListMap<K, V>) super.clone();
            clone.keySet = null;
            clone.entrySet = null;
            clone.values = null;
            clone.descendingMap = null;
            clone.buildFromSorted(this);
            return clone;
        } catch(CloneNotSupportedException e) {
            throw new InternalError();
        }
    }
    
    
    
    
    /**
     * Main insertion method.  Adds element if not present, or
     * replaces value if present and onlyIfAbsent is false.
     *
     * @param key          the key
     * @param value        the value that must be associated with key
     * @param onlyIfAbsent if should not insert if already present
     *
     * @return the old value, or null if newly inserted
     */
    /*
     * ?????????????????????key-value?????????Map??????????????????
     * onlyIfAbsent???true???????????????????????????????????????????????????????????????????????????
     */
    private V doPut(K key, V value, boolean onlyIfAbsent) {
        if(key == null) {
            throw new NullPointerException();
        }
        
        Comparator<? super K> cmp = comparator;
        
        for(; ; ) {
            Index<K, V> h;
            Node<K, V> b;
            
            VarHandle.acquireFence();
            
            /* number of levels descended */
            int levels = 0; // ????????????
            
            /* try to initialize */
            // ?????????????????????????????????????????????????????????
            if((h = head) == null) {
                // ?????????????????????
                Node<K, V> base = new Node<>(null, null, null);
                
                // ?????????????????????
                h = new Index<K, V>(base, null, null);
                
                // ???????????????head???h
                if(HEAD.compareAndSet(this, null, h)) {
                    b = base;
                } else {
                    b = null;
                }
            } else {
                Index<K, V> q = h;
                
                Index<K, V> r;
                Index<K, V> d;
                
                // count while descending
                while(true) {
                    
                    // ????????????
                    while((r = q.right) != null) {
                        Node<K, V> p;   // ?????????????????????
                        K k;            // ??????????????????key
                        
                        // ???????????????
                        if((p = r.node) == null || (k = p.key) == null || p.val == null) {
                            // ????????????q.right?????????r.right
                            RIGHT.compareAndSet(q, r, r.right);
                            
                            // ??????key > r????????????????????????
                        } else if(cpr(cmp, key, k)>0) {
                            q = r;
                        } else {
                            break;
                        }
                    }
                    
                    // ????????????
                    if((d = q.down) != null) {
                        ++levels;   // ??????????????????
                        q = d;
                        
                        // ??????????????????????????????????????????????????????????????????
                    } else {
                        b = q.node;
                        break;
                    }
                }// while(true)
            }
            
            if(b != null) {
                Node<K, V> z = null;              // new node, if inserted
                
                while(true) {                     // find insertion point
                    Node<K, V> n, p;
                    K k;
                    V v;
                    int c;
                    
                    // ??????????????????????????????????????????????????????
                    if((n = b.next) == null) {
                        // if empty, type check key now
                        if(b.key == null) {
                            cpr(cmp, key, key);
                        }
                        
                        c = -1;
                    } else if((k = n.key) == null) {
                        break;                   // can't append; restart
                    } else if((v = n.val) == null) {
                        // ???b?????????n???????????????????????????b???n????????????
                        unlinkNode(b, n);
                        
                        c = 1;
                        
                        // ??????????????????
                    } else if((c = cpr(cmp, key, k))>0) {
                        b = n;
                        
                        // ??????????????????????????????????????????????????????
                    } else if(c == 0 && (onlyIfAbsent || VAL.compareAndSet(n, v, value))) {
                        return v;
                    }
                    
                    // ???????????????
                    if(c<0 && NEXT.compareAndSet(b, n, p = new Node<K, V>(key, value, n))) {
                        z = p;
                        break;
                    }
                }// while(true)
                
                if(z != null) {
                    int lr = ThreadLocalRandom.nextSecondarySeed();
                    
                    // add indices with 1/4 prob
                    int prob = lr & 0x3;
                    
                    // ???1/4????????????0?????????????????????1/4?????????????????????
                    if(prob == 0) {
                        int hr = ThreadLocalRandom.nextSecondarySeed();
                        
                        long rnd = ((long) hr << 32) | ((long) lr & 0xffffffffL);
                        
                        // levels to descend before add
                        int skips = levels;
                        
                        Index<K, V> x = null;
                        
                        // create at most 62 indices
                        while(true) {
                            // ????????????
                            x = new Index<K, V>(z, x, null);
                            
                            if(rnd >= 0L || --skips<0) {
                                break;
                            } else {
                                rnd <<= 1;
                            }
                        }// while(true)
                        
                        // ???????????????????????????????????????
                        boolean sucess = addIndices(h, skips, x, cmp);
                        
                        /* try to add new level */
                        // ????????????????????????
                        if(sucess && skips<0 && head == h) {
                            Index<K, V> hx = new Index<>(z, x, null);
                            Index<K, V> nh = new Index<>(h.node, h, hx);
                            HEAD.compareAndSet(this, h, nh);
                        }
                        
                        /* deleted while adding indices */
                        // ???????????????
                        if(z.val == null) {
                            // ??????????????????key??????????????????????????????????????????????????????????????????????????????
                            findPredecessor(key, cmp); // clean
                        }
                    }// if(prob == 0)
                    
                    // ??????????????????
                    addCount(1L);
                    
                    return null;
                }// if(z != null)
            }// if(b != null)
        }// for(; ; )
    }
    
    /**
     * Gets value for key. Same idea as findNode, except skips over
     * deletions and markers, and returns first encountered value to
     * avoid possibly inconsistent rereads.
     *
     * @param key the key
     *
     * @return the value, or null if absent
     */
    // ???????????????key???????????????value??????????????????????????????null
    private V doGet(Object key) {
        Index<K, V> q;
        
        VarHandle.acquireFence();
        
        if(key == null) {
            throw new NullPointerException();
        }
        
        Comparator<? super K> cmp = comparator;
        
        V result = null;
        
        if((q = head) == null) {
            return result;
        }
        
        Index<K, V> r;  // ?????????
        Index<K, V> d;  // ?????????
        
        // ?????????????????????
outer:
        for(; ; ) {
            // ????????????
            while((r = q.right) != null) {
                Node<K, V> p;
                K k;
                V v;
                int c;
                if((p = r.node) == null || (k = p.key) == null || (v = p.val) == null) {
                    RIGHT.compareAndSet(q, r, r.right);
                    
                    // ??????key > k????????????????????????
                } else if((c = cpr(cmp, key, k))>0) {
                    q = r;
                    
                    // ??????????????????
                } else if(c == 0) {
                    result = v;
                    break outer;
                } else {
                    break;
                }
            }
            
            // ????????????
            if((d = q.down) != null) {
                q = d;
            } else {
                Node<K, V> b, n;
                
                if((b = q.node) != null) {
                    // ????????????
                    while((n = b.next) != null) {
                        V v;
                        int c;
                        K k = n.key;
                        if((v = n.val) == null || k == null || (c = cpr(cmp, key, k))>0) {
                            b = n;
                        } else {
                            if(c == 0) {
                                result = v;
                            }
                            break;
                        }
                    }
                }
                
                break;
            }
        }// for(; ; )
        
        return result;
    }
    
    /**
     * Main deletion method. Locates node, nulls value, appends a
     * deletion marker, unlinks predecessor, removes associated index
     * nodes, and possibly reduces head index level.
     *
     * @param key   the key
     * @param value if non-null, the value that must be
     *              associated with key
     *
     * @return the node, or null if not found
     */
    // ??????????????????key????????????????????????????????????????????????
    final V doRemove(Object key, Object value) {
        if(key == null) {
            throw new NullPointerException();
        }
        
        Comparator<? super K> cmp = comparator;
        
        V result = null;
        
        Node<K, V> b;

outer:
        while((b = findPredecessor(key, cmp)) != null && result == null) {
            for(; ; ) {
                Node<K, V> n;
                K k;
                V v;
                int c;
                
                if((n = b.next) == null) {
                    break outer;
                } else if((k = n.key) == null) {
                    break;
                } else if((v = n.val) == null) {
                    unlinkNode(b, n);
                } else if((c = cpr(cmp, key, k))>0) {
                    b = n;
                } else if(c<0) {
                    break outer;
                } else if(value != null && !value.equals(v)) {
                    break outer;
                } else if(VAL.compareAndSet(n, v, null)) {
                    result = v;
                    unlinkNode(b, n);
                    break; // loop to clean up
                }
            }// for(; ; )
        }
        
        if(result != null) {
            tryReduceLevel();
            addCount(-1L);
        }
        
        return result;
    }
    
    
    /**
     * Returns an index node with key strictly less than given key.
     * Also unlinks indexes to deleted nodes found along the way.
     * Callers rely on this side-effect of clearing indices to deleted nodes.
     *
     * @param key if nonnull the key
     *
     * @return a predecessor node of key, or null if uninitialized or null key
     */
    /*
     * ??????????????????key????????????????????????????????????
     *
     * ??????????????????????????????????????????key???????????????key???????????????
     * ?????????????????????0 2 4 6 8
     * ????????????6??????????????????????????????4???????????????5???????????????????????????4
     *
     * ?????????????????????????????????????????????
     */
    private Node<K, V> findPredecessor(Object key, Comparator<? super K> cmp) {
        Index<K, V> q;
        VarHandle.acquireFence();
        
        if((q = head) == null || key == null) {
            return null;
        }
        
        Index<K, V> r;
        
        while(true) {
            while((r = q.right) != null) {
                Node<K, V> p;
                K k;
                
                if((p = r.node) == null || (k = p.key) == null || p.val == null) {  // unlink index to deleted node
                    RIGHT.compareAndSet(q, r, r.right);
                    
                    // ??????key > k????????????????????????
                } else if(cpr(cmp, key, k)>0) {
                    q = r;
                    
                    // ??????key <= r??????????????????
                } else {
                    
                    // ?????????????????????????????????????????????
                    break;
                }
            }
            
            Index<K, V> d = q.down;
            if(d != null) {
                q = d;
            } else {
                return q.node;
            }
        }
    }
    
    /**
     * Returns node holding key or null if no such, clearing out any
     * deleted nodes seen along the way.  Repeatedly traverses at
     * base-level looking for key starting at predecessor returned
     * from findPredecessor, processing base-level deletions as
     * encountered. Restarts occur, at traversal step encountering
     * node n, if n's key field is null, indicating it is a marker, so
     * its predecessor is deleted before continuing, which we help do
     * by re-finding a valid predecessor.  The traversal loops in
     * doPut, doRemove, and findNear all include the same checks.
     *
     * @param key the key
     *
     * @return node holding key, or null if no such
     */
    // ????????????key????????????????????????????????????null
    private Node<K, V> findNode(Object key) {
        if(key == null) {
            throw new NullPointerException(); // don't postpone errors
        }
        
        Comparator<? super K> cmp = comparator;
        
        Node<K, V> b;
outer:
        while((b = findPredecessor(key, cmp)) != null) {
            for(; ; ) {
                Node<K, V> n;
                K k;
                V v;
                int c;
                
                if((n = b.next) == null) {
                    break outer;               // empty
                } else if((k = n.key) == null) {
                    break;                     // b is deleted
                } else if((v = n.val) == null) {
                    unlinkNode(b, n);          // n is deleted
                } else if((c = cpr(cmp, key, k))>0) {
                    b = n;
                } else if(c == 0) {
                    return n;
                } else {
                    break outer;
                }
            }
        }
        
        return null;
    }
    
    /**
     * Gets first valid node, unlinking deleted nodes if encountered.
     *
     * @return first node or null if empty
     */
    // ????????????(??????)???????????????
    final Node<K, V> findFirst() {
        // ????????????(??????)??????????????????
        Node<K, V> b = baseHead();
        if(b == null) {
            return null;
        }
        
        Node<K, V> n;
        while((n = b.next) != null) {
            if(n.val == null) {
                unlinkNode(b, n);
            } else {
                return n;
            }
        }
        
        return null;
    }
    
    /**
     * Specialized version of find to get last valid node.
     *
     * @return last node or null if empty
     */
    // ????????????(??????)?????????????????????
    final Node<K, V> findLast() {
outer:
        for(; ; ) {
            Index<K, V> q;
            Node<K, V> b;
            
            VarHandle.acquireFence();
            
            if((q = head) == null) {
                break;
            }
            
            Index<K, V> r;
            
            // ??????????????????????????????????????????
            while(true) {
                // ????????????
                while((r = q.right) != null) {
                    Node<K, V> p = r.node;
                    
                    if(p == null || p.val == null) {
                        RIGHT.compareAndSet(q, r, r.right);
                    } else {
                        q = r;
                    }
                }
                
                // ????????????
                Index<K, V> d = q.down;
                if(d != null) {
                    q = d;
                } else {
                    b = q.node;
                    break;
                }
            }
            
            // ??????????????????????????????????????????????????????????????????????????????
            if(b != null) {
                for(; ; ) {
                    Node<K, V> n = b.next;
                    
                    if(n == null) {
                        // empty
                        if(b.key == null) {
                            break outer;
                        } else {
                            return b;
                        }
                    } else if(n.key == null) {
                        break;
                    } else if(n.val == null) {
                        unlinkNode(b, n);
                    } else {
                        b = n;
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * Utility for ceiling, floor, lower, higher methods.
     *
     * @param key the key
     * @param rel the relation -- OR'ed combination of EQ, LT, GT
     *
     * @return nearest node fitting relation, or null if no such
     */
    /*
     * ??????key????????????????????????????????????key??????????????????rel???
     *
     * EQ: ??????key
     * LT: ??????key
     * GT: ??????key
     */
    final Node<K, V> findNear(K key, int rel, Comparator<? super K> cmp) {
        if(key == null) {
            throw new NullPointerException();
        }
        
        Node<K, V> result;

outer:
        while(true) {
            // ??????????????????key??????????????????????????????????????????????????????????????????????????????
            Node<K, V> b = findPredecessor(key, cmp);
            
            if(b == null) {
                result = null;
                break;                   // empty
            }
            
            for(; ; ) {
                Node<K, V> n = b.next;
                K k;
                int c;
                
                if(n == null) {
                    // ????????????<key ?????????
                    result = ((rel & LT) != 0 && b.key != null) ? b : null;
                    break outer;
                } else if((k = n.key) == null) {
                    break;
                } else if(n.val == null) {
                    unlinkNode(b, n);
                    
                    // ????????????==key???>=k?????????
                } else if(((c = cpr(cmp, key, k)) == 0 && (rel & EQ) != 0) || (c<0 && (rel & LT) == 0)) {
                    result = n;
                    break outer;
                    
                    // ????????????<key?????????
                } else if(c<=0 && (rel & LT) != 0) {
                    result = (b.key != null) ? b : null;
                    break outer;
                } else {
                    b = n;
                }
            }
        }
        
        return result;
    }
    
    /**
     * Entry snapshot version of findFirst
     */
    // ??????????????????Map?????????????????????????????????????????????????????????Entry
    final AbstractMap.SimpleImmutableEntry<K, V> findFirstEntry() {
        // ????????????(??????)??????????????????
        Node<K, V> b = baseHead();
        if(b == null) {
            return null;
        }
        
        Node<K, V> n;
        
        while((n = b.next) != null) {
            V v = n.val;
            
            if(v == null) {
                unlinkNode(b, n);
            } else {
                return new SimpleImmutableEntry<K, V>(n.key, v);
            }
        }
        
        return null;
    }
    
    /**
     * Entry version of findLast
     *
     * @return Entry for last node or null if empty
     */
    // ??????????????????Map???????????????????????????????????????????????????????????????Entry
    final AbstractMap.SimpleImmutableEntry<K, V> findLastEntry() {
        for(; ; ) {
            // ????????????(??????)?????????????????????
            Node<K, V> n = findLast();
            
            if(n == null) {
                return null;
            }
            
            V v = n.val;
            if(v != null) {
                return new SimpleImmutableEntry<K, V>(n.key, v);
            }
        }
    }
    
    /**
     * Variant of findNear returning SimpleImmutableEntry
     *
     * @param key the key
     * @param rel the relation -- OR'ed combination of EQ, LT, GT
     *
     * @return Entry fitting relation, or null if no such
     */
    /*
     * ????????????key?????????????????????????????????????????????key??????????????????rel???
     *
     * EQ: ??????key
     * LT: ??????key
     * GT: ??????key
     */
    final AbstractMap.SimpleImmutableEntry<K, V> findNearEntry(K key, int rel, Comparator<? super K> cmp) {
        for(; ; ) {
            Node<K, V> n = findNear(key, rel, cmp);
            if(n == null) {
                return null;
            }
            
            V v = n.val;
            if(v != null) {
                return new SimpleImmutableEntry<K, V>(n.key, v);
            }
        }
    }
    
    
    /**
     * Removes first entry; returns its snapshot.
     *
     * @return null if empty, else snapshot of first entry
     */
    // ??????????????????Map??????????????????????????????????????????????????????Entry?????????
    private AbstractMap.SimpleImmutableEntry<K, V> doRemoveFirstEntry() {
        Node<K, V> b = baseHead();
        if(b == null) {
            return null;
        }
        
        Node<K, V> n;
        while((n = b.next) != null) {
            V v = n.val;
            if(v == null || VAL.compareAndSet(n, v, null)) {
                K k = n.key;
                
                unlinkNode(b, n);
                
                if(v != null) {
                    tryReduceLevel();
                    
                    // ??????????????????key????????????????????????????????????
                    findPredecessor(k, comparator); // clean index
                    
                    addCount(-1L);
                    
                    return new AbstractMap.SimpleImmutableEntry<K, V>(k, v);
                }
            }
        }
        
        return null;
    }
    
    /**
     * Removes last entry; returns its snapshot.
     * Specialized variant of doRemove.
     *
     * @return null if empty, else snapshot of last entry
     */
    // ??????????????????Map????????????????????????????????????????????????????????????Entry?????????
    private Map.Entry<K, V> doRemoveLastEntry() {
outer:
        for(; ; ) {
            Index<K, V> q;
            Node<K, V> b;
            
            VarHandle.acquireFence();
            
            if((q = head) == null) {
                break;
            }
            
            for(; ; ) {
                Index<K, V> d, r;
                Node<K, V> p;
                while((r = q.right) != null) {
                    if((p = r.node) == null || p.val == null) {
                        RIGHT.compareAndSet(q, r, r.right);
                    } else if(p.next != null) {
                        q = r;  // continue only if a successor
                    } else {
                        break;
                    }
                }
                
                if((d = q.down) != null) {
                    q = d;
                } else {
                    b = q.node;
                    break;
                }
            }
            
            if(b != null) {
                for(; ; ) {
                    Node<K, V> n;
                    K k;
                    V v;
                    if((n = b.next) == null) {
                        if(b.key == null) { // empty
                            break outer;
                        } else {
                            break; // retry
                        }
                    } else if((k = n.key) == null) {
                        break;
                    } else if((v = n.val) == null) {
                        unlinkNode(b, n);
                    } else if(n.next != null) {
                        b = n;
                    } else if(VAL.compareAndSet(n, v, null)) {
                        unlinkNode(b, n);
                        
                        tryReduceLevel();
                        
                        // ??????????????????key????????????????????????????????????
                        findPredecessor(k, comparator); // clean index
                        
                        addCount(-1L);
                        
                        return new AbstractMap.SimpleImmutableEntry<K, V>(k, v);
                    }
                }
            }
        }
        
        return null;
    }
    
    
    // factory method for KeySpliterator
    final KeySpliterator<K, V> keySpliterator() {
        Index<K, V> h;
        Node<K, V> n;
        long est;
        
        VarHandle.acquireFence();
        
        if((h = head) == null) {
            n = null;
            est = 0L;
        } else {
            n = h.node;
            est = getAdderCount();
        }
        
        return new KeySpliterator<K, V>(comparator, h, n, null, est);
    }
    
    // Almost the same as keySpliterator()
    final ValueSpliterator<K,V> valueSpliterator() {
        Index<K,V> h;
        Node<K,V> n;
        long est;
        
        VarHandle.acquireFence();
        
        if ((h = head) == null) {
            n = null;
            est = 0L;
        } else {
            n = h.node;
            est = getAdderCount();
        }
        
        return new ValueSpliterator<K,V>(comparator, h, n, null, est);
    }
    
    // Almost the same as keySpliterator()
    final EntrySpliterator<K,V> entrySpliterator() {
        Index<K,V> h;
        Node<K,V> n;
        long est;
        
        VarHandle.acquireFence();
        
        if ((h = head) == null) {
            n = null;
            est = 0L;
        } else {
            n = h.node;
            est = getAdderCount();
        }
        return new EntrySpliterator<K,V>(comparator, h, n, null, est);
    }
    
    
    /**
     * Compares using comparator or natural ordering if null.
     * Called only by methods that have performed required type checks.
     */
    // ???????????????????????????x???y????????????
    @SuppressWarnings({"unchecked", "rawtypes"})
    static int cpr(Comparator c, Object x, Object y) {
        return (c != null) ? c.compare(x, y) : ((Comparable) x).compareTo(y);
    }
    
    /**
     * Tries to unlink deleted node n from predecessor b (if both
     * exist), by first splicing in a marker if not already present.
     * Upon return, node n is sure to be unlinked from b, possibly
     * via the actions of some other thread.
     *
     * @param b if nonnull, predecessor
     * @param n if nonnull, node known to be deleted
     */
    // ???b?????????n???????????????????????????b???n????????????
    static <K, V> void unlinkNode(Node<K, V> b, Node<K, V> n) {
        if(b != null && n != null) {
            Node<K, V> f, p;
            
            for(; ; ) {
                if((f = n.next) != null && f.key == null) {
                    p = f.next;               // already marked
                    break;
                } else if(NEXT.compareAndSet(n, f, new Node<K, V>(null, null, f))) {
                    p = f;                    // add marker
                    break;
                }
            }
            
            NEXT.compareAndSet(b, n, p);
        }
    }
    
    /**
     * Returns the header for base node list, or null if uninitialized
     */
    // ????????????(??????)??????????????????
    final Node<K, V> baseHead() {
        Index<K, V> h;
        VarHandle.acquireFence();
        return ((h = head) == null) ? null : h.node;
    }
    
    /**
     * Returns element count, initializing adder if necessary.
     */
    final long getAdderCount() {
        LongAdder a;
        long c;
        
        while((a = adder) == null && !ADDER.compareAndSet(this, null, a = new LongAdder())) {
            // empty
        }
        
        return ((c = a.sum())<=0L) ? 0L : c; // ignore transient negatives
    }
    
    /**
     * Adds to element count, initializing adder if necessary
     *
     * @param c count to add
     */
    // ??????????????????
    private void addCount(long c) {
        LongAdder a;
        
        // ??????adder??????????????????
        while((a = adder) == null && !ADDER.compareAndSet(this, null, a = new LongAdder())) {
            // empty
        }
        
        // ????????????adder??????
        a.add(c);
    }
    
    
    /**
     * Add indices after an insertion. Descends iteratively to the
     * highest level of insertion, then recursively, to chain index
     * nodes to lower ones. Returns null on (staleness) failure,
     * disabling higher-level insertions. Recursion depths are
     * exponentially less probable.
     *
     * @param q     starting index for current level
     * @param skips levels to skip before inserting
     * @param x     index for this insertion
     * @param cmp   comparator
     */
    static <K, V> boolean addIndices(Index<K, V> q, int skips, Index<K, V> x, Comparator<? super K> cmp) {
        Node<K, V> z;
        K key;
        
        if(x != null && (z = x.node) != null && (key = z.key) != null && q != null) { // hoist checks
            boolean retrying = false;
            
            // find splice point
            for(; ; ) {
                Index<K, V> r;
                Index<K, V> d;
                int c;
                
                if((r = q.right) == null) {
                    c = -1;
                } else {
                    Node<K, V> p;
                    K k;
                    if((p = r.node) == null || (k = p.key) == null || p.val == null) {
                        RIGHT.compareAndSet(q, r, r.right);
                        c = 0;
                    } else if((c = cpr(cmp, key, k))>0) {
                        q = r;
                    } else if(c == 0) {
                        break;                      // stale
                    }
                }
                
                if(c<0) {
                    if((d = q.down) != null && skips>0) {
                        --skips;
                        q = d;
                    } else if(d != null && !retrying && !addIndices(d, 0, x.down, cmp)) {
                        break;
                    } else {
                        x.right = r;
                        if(RIGHT.compareAndSet(q, r, x)) {
                            return true;
                        } else {
                            retrying = true;         // re-find splice point
                        }
                    }
                }
            }
        }
        
        return false;
    }
    
    /**
     * Possibly reduce head level if it has no nodes.  This method can
     * (rarely) make mistakes, in which case levels can disappear even
     * though they are about to contain index nodes. This impacts
     * performance, not correctness.  To minimize mistakes as well as
     * to reduce hysteresis, the level is reduced by one only if the
     * topmost three levels look empty. Also, if the removed level
     * looks non-empty after CAS, we try to change it back quick
     * before anyone notices our mistake! (This trick works pretty
     * well because this method will practically never make mistakes
     * unless current thread stalls immediately before first CAS, in
     * which case it is very unlikely to stall again immediately
     * afterwards, so will recover.)
     *
     * We put up with all this rather than just let levels grow
     * because otherwise, even a small map that has undergone a large
     * number of insertions and removals will have a lot of levels,
     * slowing down access more than would an occasional unwanted
     * reduction.
     */
    private void tryReduceLevel() {
        Index<K, V> h, d, e;
        
        if((h = head) != null
            && h.right == null
            && (d = h.down) != null
            && d.right == null
            && (e = d.down) != null
            && e.right == null
            && HEAD.compareAndSet(this, h, d)
            && h.right != null) {   // recheckq
            
            HEAD.compareAndSet(this, d, h);  // try to backout
        }
    }
    
    /**
     * Streamlined bulk insertion to initialize from elements of
     * given sorted map.  Call only from constructor or clone
     * method.
     */
    private void buildFromSorted(SortedMap<K, ? extends V> map) {
        if(map == null)
            throw new NullPointerException();
        Iterator<? extends Map.Entry<? extends K, ? extends V>> it = map.entrySet().iterator();
        
        /*
         * Add equally spaced indices at log intervals, using the bits
         * of count during insertion. The maximum possible resulting
         * level is less than the number of bits in a long (64). The
         * preds array tracks the current rightmost node at each
         * level.
         */
        @SuppressWarnings("unchecked")
        Index<K, V>[] preds = (Index<K, V>[]) new Index<?, ?>[64];
        Node<K, V> bp = new Node<K, V>(null, null, null);
        Index<K, V> h = preds[0] = new Index<K, V>(bp, null, null);
        long count = 0;
        
        while(it.hasNext()) {
            Map.Entry<? extends K, ? extends V> e = it.next();
            K k = e.getKey();
            V v = e.getValue();
            if(k == null || v == null)
                throw new NullPointerException();
            Node<K, V> z = new Node<K, V>(k, v, null);
            bp = bp.next = z;
            if((++count & 3L) == 0L) {
                long m = count >>> 2;
                int i = 0;
                Index<K, V> idx = null, q;
                do {
                    idx = new Index<K, V>(z, idx, null);
                    if((q = preds[i]) == null)
                        preds[i] = h = new Index<K, V>(h.node, h, idx);
                    else
                        preds[i] = q.right = idx;
                } while(++i<preds.length && ((m >>>= 1) & 1L) != 0L);
            }
        }
        if(count != 0L) {
            VarHandle.releaseFence(); // emulate volatile stores
            addCount(count);
            head = h;
            VarHandle.fullFence();
        }
    }
    
    /**
     * Helper method for EntrySet.removeIf.
     */
    boolean removeEntryIf(Predicate<? super Entry<K, V>> function) {
        if(function == null)
            throw new NullPointerException();
        boolean removed = false;
        Node<K, V> b, n;
        V v;
        if((b = baseHead()) != null) {
            while((n = b.next) != null) {
                if((v = n.val) != null) {
                    K k = n.key;
                    Map.Entry<K, V> e = new AbstractMap.SimpleImmutableEntry<>(k, v);
                    if(function.test(e) && remove(k, v))
                        removed = true;
                }
                b = n;
            }
        }
        return removed;
    }
    
    /**
     * Helper method for Values.removeIf.
     */
    boolean removeValueIf(Predicate<? super V> function) {
        if(function == null)
            throw new NullPointerException();
        boolean removed = false;
        Node<K, V> b, n;
        V v;
        if((b = baseHead()) != null) {
            while((n = b.next) != null) {
                if((v = n.val) != null && function.test(v) && remove(n.key, v))
                    removed = true;
                b = n;
            }
        }
        return removed;
    }
    
    static final <E> List<E> toList(Collection<E> c) {
        // Using size() here would be a pessimization.
        ArrayList<E> list = new ArrayList<E>();
        for(E e : c)
            list.add(e);
        return list;
    }
    
    
    
    
    
    
    /**
     * Nodes hold keys and values, and are singly linked in sorted
     * order, possibly with some intervening marker nodes. The list is
     * headed by a header node accessible as head.node. Headers and
     * marker nodes have null keys. The val field (but currently not
     * the key field) is nulled out upon deletion.
     */
    // ???????????????????????????????????????????????????????????????
    static final class Node<K,V> {
        final K key; // currently, never detached
        V val;
        
        Node<K,V> next;
        
        Node(K key, V value, Node<K,V> next) {
            this.key = key;
            this.val = value;
            this.next = next;
        }
    }
    
    /**
     * Index nodes represent the levels of the skip list.
     */
    // ????????????????????????????????????????????????????????????????????????
    static final class Index<K,V> {
        final Node<K,V> node;  // currently, never detached
        
        final Index<K,V> down;
        Index<K,V> right;
        
        Index(Node<K,V> node, Index<K,V> down, Index<K,V> right) {
            this.node = node;
            this.down = down;
            this.right = right;
        }
    }
    
    
    
    /*
     * View classes are static, delegating to a ConcurrentNavigableMap
     * to allow use by SubMaps, which outweighs the ugliness of
     * needing type-tests for Iterator methods.
     */
    
    // key?????????
    static final class KeySet<K, V> extends AbstractSet<K> implements NavigableSet<K> {
        final ConcurrentNavigableMap<K, V> m;
        
        KeySet(ConcurrentNavigableMap<K, V> map) {
            m = map;
        }
        
        public int size() {
            return m.size();
        }
        
        public boolean isEmpty() {
            return m.isEmpty();
        }
        
        public boolean contains(Object o) {
            return m.containsKey(o);
        }
        
        public boolean remove(Object o) {
            return m.remove(o) != null;
        }
        
        public void clear() {
            m.clear();
        }
        
        public K lower(K e) {
            return m.lowerKey(e);
        }
        
        public K floor(K e) {
            return m.floorKey(e);
        }
        
        public K ceiling(K e) {
            return m.ceilingKey(e);
        }
        
        public K higher(K e) {
            return m.higherKey(e);
        }
        
        public Comparator<? super K> comparator() {
            return m.comparator();
        }
        
        public K first() {
            return m.firstKey();
        }
        
        public K last() {
            return m.lastKey();
        }
        
        public K pollFirst() {
            Map.Entry<K, V> e = m.pollFirstEntry();
            return (e == null) ? null : e.getKey();
        }
        
        public K pollLast() {
            Map.Entry<K, V> e = m.pollLastEntry();
            return (e == null) ? null : e.getKey();
        }
        
        public Iterator<K> iterator() {
            return (m instanceof ConcurrentSkipListMap) ? ((ConcurrentSkipListMap<K, V>) m).new KeyIterator() : ((SubMap<K, V>) m).new SubMapKeyIterator();
        }
        
        public boolean equals(Object o) {
            if(o == this) {
                return true;
            }
            
            if(!(o instanceof Set)) {
                return false;
            }
            
            Collection<?> c = (Collection<?>) o;
            try {
                return containsAll(c) && c.containsAll(this);
            } catch(ClassCastException | NullPointerException unused) {
                return false;
            }
        }
        
        public Object[] toArray() {
            return toList(this).toArray();
        }
        
        public <T> T[] toArray(T[] a) {
            return toList(this).toArray(a);
        }
        
        public Iterator<K> descendingIterator() {
            return descendingSet().iterator();
        }
        
        public NavigableSet<K> subSet(K fromElement, boolean fromInclusive, K toElement, boolean toInclusive) {
            return new KeySet<>(m.subMap(fromElement, fromInclusive, toElement, toInclusive));
        }
        
        public NavigableSet<K> headSet(K toElement, boolean inclusive) {
            return new KeySet<>(m.headMap(toElement, inclusive));
        }
        
        public NavigableSet<K> tailSet(K fromElement, boolean inclusive) {
            return new KeySet<>(m.tailMap(fromElement, inclusive));
        }
        
        public NavigableSet<K> subSet(K fromElement, K toElement) {
            return subSet(fromElement, true, toElement, false);
        }
        
        public NavigableSet<K> headSet(K toElement) {
            return headSet(toElement, false);
        }
        
        public NavigableSet<K> tailSet(K fromElement) {
            return tailSet(fromElement, true);
        }
        
        public NavigableSet<K> descendingSet() {
            return new KeySet<>(m.descendingMap());
        }
        
        public Spliterator<K> spliterator() {
            return (m instanceof ConcurrentSkipListMap) ? ((ConcurrentSkipListMap<K, V>) m).keySpliterator() : ((SubMap<K, V>) m).new SubMapKeyIterator();
        }
    }
    
    // value?????????
    static final class Values<K, V> extends AbstractCollection<V> {
        final ConcurrentNavigableMap<K, V> m;
        
        Values(ConcurrentNavigableMap<K, V> map) {
            m = map;
        }
        
        public Iterator<V> iterator() {
            return (m instanceof ConcurrentSkipListMap) ? ((ConcurrentSkipListMap<K, V>) m).new ValueIterator() : ((SubMap<K, V>) m).new SubMapValueIterator();
        }
        
        public int size() {
            return m.size();
        }
        
        public boolean isEmpty() {
            return m.isEmpty();
        }
        
        public boolean contains(Object o) {
            return m.containsValue(o);
        }
        
        public void clear() {
            m.clear();
        }
        
        public Object[] toArray() {
            return toList(this).toArray();
        }
        
        public <T> T[] toArray(T[] a) {
            return toList(this).toArray(a);
        }
        
        public Spliterator<V> spliterator() {
            return (m instanceof ConcurrentSkipListMap) ? ((ConcurrentSkipListMap<K, V>) m).valueSpliterator() : ((SubMap<K, V>) m).new SubMapValueIterator();
        }
        
        public boolean removeIf(Predicate<? super V> filter) {
            if(filter == null) {
                throw new NullPointerException();
            }
            
            if(m instanceof ConcurrentSkipListMap) {
                return ((ConcurrentSkipListMap<K, V>) m).removeValueIf(filter);
            }
            
            // else use iterator
            Iterator<Map.Entry<K, V>> it = ((SubMap<K, V>) m).new SubMapEntryIterator();
            
            boolean removed = false;
            while(it.hasNext()) {
                Map.Entry<K, V> e = it.next();
                V v = e.getValue();
                if(filter.test(v) && m.remove(e.getKey(), v)) {
                    removed = true;
                }
            }
            
            return removed;
        }
    }
    
    // key-value?????????
    static final class EntrySet<K, V> extends AbstractSet<Map.Entry<K, V>> {
        final ConcurrentNavigableMap<K, V> m;
        
        EntrySet(ConcurrentNavigableMap<K, V> map) {
            m = map;
        }
        
        public Iterator<Map.Entry<K, V>> iterator() {
            return (m instanceof ConcurrentSkipListMap) ? ((ConcurrentSkipListMap<K, V>) m).new EntryIterator() : ((SubMap<K, V>) m).new SubMapEntryIterator();
        }
        
        public boolean contains(Object o) {
            if(!(o instanceof Map.Entry)) {
                return false;
            }
            
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            V v = m.get(e.getKey());
            return v != null && v.equals(e.getValue());
        }
        
        public boolean remove(Object o) {
            if(!(o instanceof Map.Entry))
                return false;
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            return m.remove(e.getKey(), e.getValue());
        }
        
        public boolean isEmpty() {
            return m.isEmpty();
        }
        
        public int size() {
            return m.size();
        }
        
        public void clear() {
            m.clear();
        }
        
        public boolean equals(Object o) {
            if(o == this) {
                return true;
            }
            
            if(!(o instanceof Set)) {
                return false;
            }
            
            Collection<?> c = (Collection<?>) o;
            try {
                return containsAll(c) && c.containsAll(this);
            } catch(ClassCastException | NullPointerException unused) {
                return false;
            }
        }
        
        public Object[] toArray() {
            return toList(this).toArray();
        }
        
        public <T> T[] toArray(T[] a) {
            return toList(this).toArray(a);
        }
        
        public Spliterator<Map.Entry<K, V>> spliterator() {
            return (m instanceof ConcurrentSkipListMap) ? ((ConcurrentSkipListMap<K, V>) m).entrySpliterator() : ((SubMap<K, V>) m).new SubMapEntryIterator();
        }
        
        public boolean removeIf(Predicate<? super Entry<K, V>> filter) {
            if(filter == null) {
                throw new NullPointerException();
            }
            
            if(m instanceof ConcurrentSkipListMap) {
                return ((ConcurrentSkipListMap<K, V>) m).removeEntryIf(filter);
            }
            
            // else use iterator
            Iterator<Map.Entry<K, V>> it = ((SubMap<K, V>) m).new SubMapEntryIterator();
            
            boolean removed = false;
            while(it.hasNext()) {
                Map.Entry<K, V> e = it.next();
                if(filter.test(e) && m.remove(e.getKey(), e.getValue())) {
                    removed = true;
                }
            }
            
            return removed;
        }
    }
    
    
    
    /**
     * Base of iterator classes
     */
    // ?????????
    abstract class Iter<T> implements Iterator<T> {
        /** the last node returned by next() */
        Node<K, V> lastReturned;
        
        /** the next node to return from next(); */
        Node<K, V> next;
        
        /** Cache of next value field to maintain weak consistency */
        V nextValue;
        
        /** Initializes ascending iterator for entire range. */
        Iter() {
            advance(baseHead());
        }
        
        public final boolean hasNext() {
            return next != null;
        }
        
        public final void remove() {
            Node<K, V> n;
            K k;
            if((n = lastReturned) == null || (k = n.key) == null) {
                throw new IllegalStateException();
            }
            // It would not be worth all of the overhead to directly
            // unlink from here. Using remove is fast enough.
            ConcurrentSkipListMap.this.remove(k);
            lastReturned = null;
        }
        
        /** Advances next to higher entry. */
        final void advance(Node<K, V> b) {
            Node<K, V> n = null;
            V v = null;
            if((lastReturned = b) != null) {
                while((n = b.next) != null && (v = n.val) == null) {
                    b = n;
                }
            }
            nextValue = v;
            next = n;
        }
    }
    
    // key????????????
    final class KeyIterator extends Iter<K> {
        public K next() {
            Node<K, V> n;
            if((n = next) == null) {
                throw new NoSuchElementException();
            }
            K k = n.key;
            advance(n);
            return k;
        }
    }
    
    // value????????????
    final class ValueIterator extends Iter<V> {
        public V next() {
            V v;
            if((v = nextValue) == null) {
                throw new NoSuchElementException();
            }
            advance(next);
            return v;
        }
    }
    
    // entry????????????
    final class EntryIterator extends Iter<Map.Entry<K, V>> {
        public Map.Entry<K, V> next() {
            Node<K, V> n;
            if((n = next) == null) {
                throw new NoSuchElementException();
            }
            K k = n.key;
            V v = nextValue;
            advance(n);
            return new AbstractMap.SimpleImmutableEntry<K, V>(k, v);
        }
    }
    
    
    
    /**
     * Base class providing common structure for Spliterators.
     * (Although not all that much common functionality; as usual for
     * view classes, details annoyingly vary in key, value, and entry
     * subclasses in ways that are not worth abstracting out for
     * internal classes.)
     *
     * The basic split strategy is to recursively descend from top
     * level, row by row, descending to next row when either split
     * off, or the end of row is encountered. Control of the number of
     * splits relies on some statistical estimation: The expected
     * remaining number of elements of a skip list when advancing
     * either across or down decreases by about 25%.
     */
    // ?????????????????????
    abstract static class CSLMSpliterator<K, V> {
        final Comparator<? super K> comparator;
        final K fence;     // exclusive upper bound for keys, or null if to end
        Index<K, V> row;    // the level to split out
        Node<K, V> current; // current traversal node; initialize at origin
        long est;          // size estimate
        
        CSLMSpliterator(Comparator<? super K> comparator, Index<K, V> row, Node<K, V> origin, K fence, long est) {
            this.comparator = comparator;
            this.row = row;
            this.current = origin;
            this.fence = fence;
            this.est = est;
        }
        
        public final long estimateSize() {
            return est;
        }
    }
    
    // key?????????????????????
    static final class KeySpliterator<K, V> extends CSLMSpliterator<K, V> implements Spliterator<K> {
        KeySpliterator(Comparator<? super K> comparator, Index<K, V> row, Node<K, V> origin, K fence, long est) {
            super(comparator, row, origin, fence, est);
        }
        
        public final Comparator<? super K> getComparator() {
            return comparator;
        }
        
        public KeySpliterator<K, V> trySplit() {
            Node<K, V> e;
            K ek;
            Comparator<? super K> cmp = comparator;
            K f = fence;
            if((e = current) != null && (ek = e.key) != null) {
                for(Index<K, V> q = row; q != null; q = row = q.down) {
                    Index<K, V> s;
                    Node<K, V> b, n;
                    K sk;
                    if((s = q.right) != null
                        && (b = s.node) != null
                        && (n = b.next) != null
                        && n.val != null
                        && (sk = n.key) != null
                        && cpr(cmp, sk, ek)>0
                        && (f == null || cpr(cmp, sk, f)<0)) {
                        current = n;
                        Index<K, V> r = q.down;
                        row = (s.right != null) ? s : s.down;
                        est -= est >>> 2;
                        return new KeySpliterator<K, V>(cmp, r, e, sk, est);
                    }
                }
            }
            return null;
        }
        
        public void forEachRemaining(Consumer<? super K> action) {
            if(action == null) {
                throw new NullPointerException();
            }
            
            Comparator<? super K> cmp = comparator;
            
            K f = fence;
            Node<K, V> e = current;
            current = null;
            for(; e != null; e = e.next) {
                K k;
                if((k = e.key) != null && f != null && cpr(cmp, f, k)<=0) {
                    break;
                }
                
                if(e.val != null) {
                    action.accept(k);
                }
            }
        }
        
        public boolean tryAdvance(Consumer<? super K> action) {
            if(action == null) {
                throw new NullPointerException();
            }
            
            Comparator<? super K> cmp = comparator;
            K f = fence;
            Node<K, V> e = current;
            for(; e != null; e = e.next) {
                K k;
                
                if((k = e.key) != null && f != null && cpr(cmp, f, k)<=0) {
                    e = null;
                    break;
                }
                
                if(e.val != null) {
                    current = e.next;
                    action.accept(k);
                    return true;
                }
            }
            current = e;
            return false;
        }
        
        public int characteristics() {
            return Spliterator.DISTINCT | Spliterator.SORTED | Spliterator.ORDERED | Spliterator.CONCURRENT | Spliterator.NONNULL;
        }
    }
    
    // value?????????????????????
    static final class ValueSpliterator<K, V> extends CSLMSpliterator<K, V> implements Spliterator<V> {
        ValueSpliterator(Comparator<? super K> comparator, Index<K, V> row, Node<K, V> origin, K fence, long est) {
            super(comparator, row, origin, fence, est);
        }
        
        public ValueSpliterator<K, V> trySplit() {
            Node<K, V> e;
            K ek;
            Comparator<? super K> cmp = comparator;
            K f = fence;
            if((e = current) != null && (ek = e.key) != null) {
                for(Index<K, V> q = row; q != null; q = row = q.down) {
                    Index<K, V> s;
                    Node<K, V> b, n;
                    K sk;
                    if((s = q.right) != null
                        && (b = s.node) != null
                        && (n = b.next) != null
                        && n.val != null
                        && (sk = n.key) != null
                        && cpr(cmp, sk, ek)>0
                        && (f == null || cpr(cmp, sk, f)<0)) {
                        current = n;
                        Index<K, V> r = q.down;
                        row = (s.right != null) ? s : s.down;
                        est -= est >>> 2;
                        return new ValueSpliterator<K, V>(cmp, r, e, sk, est);
                    }
                }
            }
            return null;
        }
        
        public void forEachRemaining(Consumer<? super V> action) {
            if(action == null) {
                throw new NullPointerException();
            }
            
            Comparator<? super K> cmp = comparator;
            K f = fence;
            Node<K, V> e = current;
            current = null;
            for(; e != null; e = e.next) {
                K k;
                V v;
                
                if((k = e.key) != null && f != null && cpr(cmp, f, k)<=0) {
                    break;
                }
                
                if((v = e.val) != null) {
                    action.accept(v);
                }
            }
        }
        
        public boolean tryAdvance(Consumer<? super V> action) {
            if(action == null) {
                throw new NullPointerException();
            }
            
            Comparator<? super K> cmp = comparator;
            K f = fence;
            Node<K, V> e = current;
            for(; e != null; e = e.next) {
                K k;
                V v;
                if((k = e.key) != null && f != null && cpr(cmp, f, k)<=0) {
                    e = null;
                    break;
                }
                
                if((v = e.val) != null) {
                    current = e.next;
                    action.accept(v);
                    return true;
                }
            }
            current = e;
            return false;
        }
        
        public int characteristics() {
            return Spliterator.CONCURRENT | Spliterator.ORDERED | Spliterator.NONNULL;
        }
    }
    
    // entry?????????????????????
    static final class EntrySpliterator<K, V> extends CSLMSpliterator<K, V> implements Spliterator<Map.Entry<K, V>> {
        EntrySpliterator(Comparator<? super K> comparator, Index<K, V> row, Node<K, V> origin, K fence, long est) {
            super(comparator, row, origin, fence, est);
        }
        
        public final Comparator<Map.Entry<K, V>> getComparator() {
            // Adapt or create a key-based comparator
            if(comparator != null) {
                return Map.Entry.comparingByKey(comparator);
            } else {
                return (Comparator<Map.Entry<K, V>> & Serializable) (e1, e2) -> {
                    @SuppressWarnings("unchecked")
                    Comparable<? super K> k1 = (Comparable<? super K>) e1.getKey();
                    return k1.compareTo(e2.getKey());
                };
            }
        }
        
        public EntrySpliterator<K, V> trySplit() {
            Node<K, V> e;
            K ek;
            Comparator<? super K> cmp = comparator;
            K f = fence;
            if((e = current) != null && (ek = e.key) != null) {
                for(Index<K, V> q = row; q != null; q = row = q.down) {
                    Index<K, V> s;
                    Node<K, V> b, n;
                    K sk;
                    if((s = q.right) != null
                        && (b = s.node) != null
                        && (n = b.next) != null
                        && n.val != null
                        && (sk = n.key) != null
                        && cpr(cmp, sk, ek)>0
                        && (f == null || cpr(cmp, sk, f)<0)) {
                        current = n;
                        Index<K, V> r = q.down;
                        row = (s.right != null) ? s : s.down;
                        est -= est >>> 2;
                        return new EntrySpliterator<K, V>(cmp, r, e, sk, est);
                    }
                }
            }
            return null;
        }
        
        public void forEachRemaining(Consumer<? super Map.Entry<K, V>> action) {
            if(action == null) {
                throw new NullPointerException();
            }
            
            Comparator<? super K> cmp = comparator;
            K f = fence;
            Node<K, V> e = current;
            current = null;
            for(; e != null; e = e.next) {
                K k;
                V v;
                if((k = e.key) != null && f != null && cpr(cmp, f, k)<=0) {
                    break;
                }
                
                if((v = e.val) != null) {
                    action.accept(new AbstractMap.SimpleImmutableEntry<K, V>(k, v));
                }
            }
        }
        
        public boolean tryAdvance(Consumer<? super Map.Entry<K, V>> action) {
            if(action == null) {
                throw new NullPointerException();
            }
            
            Comparator<? super K> cmp = comparator;
            K f = fence;
            Node<K, V> e = current;
            for(; e != null; e = e.next) {
                K k;
                V v;
                if((k = e.key) != null && f != null && cpr(cmp, f, k)<=0) {
                    e = null;
                    break;
                }
                
                if((v = e.val) != null) {
                    current = e.next;
                    action.accept(new AbstractMap.SimpleImmutableEntry<K, V>(k, v));
                    return true;
                }
            }
            current = e;
            return false;
        }
        
        public int characteristics() {
            return Spliterator.DISTINCT | Spliterator.SORTED | Spliterator.ORDERED | Spliterator.CONCURRENT | Spliterator.NONNULL;
        }
    }
    
    
    
    /**
     * Submaps returned by {@link ConcurrentSkipListMap} submap operations
     * represent a subrange of mappings of their underlying maps.
     * Instances of this class support all methods of their underlying
     * maps, differing in that mappings outside their range are ignored,
     * and attempts to add mappings outside their ranges result in {@link
     * IllegalArgumentException}.  Instances of this class are constructed
     * only using the {@code subMap}, {@code headMap}, and {@code tailMap}
     * methods of their underlying maps.
     *
     * @serial include
     */
    static final class SubMap<K, V> extends AbstractMap<K, V> implements ConcurrentNavigableMap<K, V>, Serializable {
        private static final long serialVersionUID = -7647078645895051609L;
        
        /** Underlying map */
        final ConcurrentSkipListMap<K, V> m;
        
        /** direction */
        final boolean isDescending;
        
        /** lower bound key, or null if from start */
        private final K lo;
        /** upper bound key, or null if to end */
        private final K hi;
        
        /** inclusion flag for lo */
        private final boolean loInclusive;
        /** inclusion flag for hi */
        private final boolean hiInclusive;
        
        // Lazily initialized view holders
        private transient KeySet<K, V> keySetView;
        private transient Values<K, V> valuesView;
        private transient EntrySet<K, V> entrySetView;
        
        /**
         * Creates a new submap, initializing all fields.
         */
        SubMap(ConcurrentSkipListMap<K, V> map, K fromKey, boolean fromInclusive, K toKey, boolean toInclusive, boolean isDescending) {
            Comparator<? super K> cmp = map.comparator;
            if(fromKey != null && toKey != null && cpr(cmp, fromKey, toKey)>0)
                throw new IllegalArgumentException("inconsistent range");
            this.m = map;
            this.lo = fromKey;
            this.hi = toKey;
            this.loInclusive = fromInclusive;
            this.hiInclusive = toInclusive;
            this.isDescending = isDescending;
        }
        
        public boolean containsKey(Object key) {
            if(key == null)
                throw new NullPointerException();
            return inBounds(key, m.comparator) && m.containsKey(key);
        }
        
        public V get(Object key) {
            if(key == null)
                throw new NullPointerException();
            return (!inBounds(key, m.comparator)) ? null : m.get(key);
        }
        
        public V put(K key, V value) {
            checkKeyBounds(key, m.comparator);
            return m.put(key, value);
        }
        
        public V remove(Object key) {
            return (!inBounds(key, m.comparator)) ? null : m.remove(key);
        }
        
        public int size() {
            Comparator<? super K> cmp = m.comparator;
            long count = 0;
            for(ConcurrentSkipListMap.Node<K, V> n = loNode(cmp); isBeforeEnd(n, cmp); n = n.next) {
                if(n.val != null) {
                    ++count;
                }
            }
            return count >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
        }
        
        public boolean isEmpty() {
            Comparator<? super K> cmp = m.comparator;
            return !isBeforeEnd(loNode(cmp), cmp);
        }
        
        public boolean containsValue(Object value) {
            if(value == null) {
                throw new NullPointerException();
            }
            
            Comparator<? super K> cmp = m.comparator;
            for(ConcurrentSkipListMap.Node<K, V> n = loNode(cmp); isBeforeEnd(n, cmp); n = n.next) {
                V v = n.val;
                if(v != null && value.equals(v)) {
                    return true;
                }
            }
            
            return false;
        }
        
        public void clear() {
            Comparator<? super K> cmp = m.comparator;
            for(ConcurrentSkipListMap.Node<K, V> n = loNode(cmp); isBeforeEnd(n, cmp); n = n.next) {
                if(n.val != null) {
                    m.remove(n.key);
                }
            }
        }
        
        public V putIfAbsent(K key, V value) {
            checkKeyBounds(key, m.comparator);
            return m.putIfAbsent(key, value);
        }
        
        public boolean remove(Object key, Object value) {
            return inBounds(key, m.comparator) && m.remove(key, value);
        }
        
        public boolean replace(K key, V oldValue, V newValue) {
            checkKeyBounds(key, m.comparator);
            return m.replace(key, oldValue, newValue);
        }
        
        public V replace(K key, V newValue) {
            checkKeyBounds(key, m.comparator);
            return m.replace(key, newValue);
        }
        
        public Comparator<? super K> comparator() {
            Comparator<? super K> cmp = m.comparator();
            if(isDescending) {
                return Collections.reverseOrder(cmp);
            } else {
                return cmp;
            }
        }
        
        public SubMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
            if(fromKey == null || toKey == null) {
                throw new NullPointerException();
            }
            return newSubMap(fromKey, fromInclusive, toKey, toInclusive);
        }
        
        public SubMap<K, V> headMap(K toKey, boolean inclusive) {
            if(toKey == null) {
                throw new NullPointerException();
            }
            return newSubMap(null, false, toKey, inclusive);
        }
        
        public SubMap<K, V> tailMap(K fromKey, boolean inclusive) {
            if(fromKey == null) {
                throw new NullPointerException();
            }
            return newSubMap(fromKey, inclusive, null, false);
        }
        
        public SubMap<K, V> subMap(K fromKey, K toKey) {
            return subMap(fromKey, true, toKey, false);
        }
        
        public SubMap<K, V> headMap(K toKey) {
            return headMap(toKey, false);
        }
        
        public SubMap<K, V> tailMap(K fromKey) {
            return tailMap(fromKey, true);
        }
        
        public SubMap<K, V> descendingMap() {
            return new SubMap<K, V>(m, lo, loInclusive, hi, hiInclusive, !isDescending);
        }
        
        public Map.Entry<K, V> ceilingEntry(K key) {
            return getNearEntry(key, GT | EQ);
        }
        
        public K ceilingKey(K key) {
            return getNearKey(key, GT | EQ);
        }
        
        public Map.Entry<K, V> lowerEntry(K key) {
            return getNearEntry(key, LT);
        }
        
        public K lowerKey(K key) {
            return getNearKey(key, LT);
        }
        
        public Map.Entry<K, V> floorEntry(K key) {
            return getNearEntry(key, LT | EQ);
        }
        
        public K floorKey(K key) {
            return getNearKey(key, LT | EQ);
        }
        
        public Map.Entry<K, V> higherEntry(K key) {
            return getNearEntry(key, GT);
        }
        
        public K higherKey(K key) {
            return getNearKey(key, GT);
        }
        
        public K firstKey() {
            return isDescending ? highestKey() : lowestKey();
        }
        
        public K lastKey() {
            return isDescending ? lowestKey() : highestKey();
        }
        
        public Map.Entry<K, V> firstEntry() {
            return isDescending ? highestEntry() : lowestEntry();
        }
        
        public Map.Entry<K, V> lastEntry() {
            return isDescending ? lowestEntry() : highestEntry();
        }
        
        public Map.Entry<K, V> pollFirstEntry() {
            return isDescending ? removeHighest() : removeLowest();
        }
        
        public Map.Entry<K, V> pollLastEntry() {
            return isDescending ? removeLowest() : removeHighest();
        }
        
        public NavigableSet<K> keySet() {
            KeySet<K, V> ks;
            if((ks = keySetView) != null) {
                return ks;
            }
            return keySetView = new KeySet<>(this);
        }
        
        public NavigableSet<K> navigableKeySet() {
            KeySet<K, V> ks;
            if((ks = keySetView) != null) {
                return ks;
            }
            return keySetView = new KeySet<>(this);
        }
        
        public Collection<V> values() {
            Values<K, V> vs;
            if((vs = valuesView) != null) {
                return vs;
            }
            return valuesView = new Values<>(this);
        }
        
        public Set<Map.Entry<K, V>> entrySet() {
            EntrySet<K, V> es;
            if((es = entrySetView) != null) {
                return es;
            }
            return entrySetView = new EntrySet<K, V>(this);
        }
        
        public NavigableSet<K> descendingKeySet() {
            return descendingMap().navigableKeySet();
        }
        
        /**
         * Utility to create submaps, where given bounds override
         * unbounded(null) ones and/or are checked against bounded ones.
         */
        SubMap<K, V> newSubMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
            Comparator<? super K> cmp = m.comparator;
            if(isDescending) { // flip senses
                K tk = fromKey;
                fromKey = toKey;
                toKey = tk;
                boolean ti = fromInclusive;
                fromInclusive = toInclusive;
                toInclusive = ti;
            }
            
            if(lo != null) {
                if(fromKey == null) {
                    fromKey = lo;
                    fromInclusive = loInclusive;
                } else {
                    int c = cpr(cmp, fromKey, lo);
                    if(c<0 || (c == 0 && !loInclusive && fromInclusive)) {
                        throw new IllegalArgumentException("key out of range");
                    }
                }
            }
            
            if(hi != null) {
                if(toKey == null) {
                    toKey = hi;
                    toInclusive = hiInclusive;
                } else {
                    int c = cpr(cmp, toKey, hi);
                    if(c>0 || (c == 0 && !hiInclusive && toInclusive)) {
                        throw new IllegalArgumentException("key out of range");
                    }
                }
            }
            return new SubMap<K, V>(m, fromKey, fromInclusive, toKey, toInclusive, isDescending);
        }
        
        boolean tooLow(Object key, Comparator<? super K> cmp) {
            int c;
            return (lo != null && ((c = cpr(cmp, key, lo))<0 || (c == 0 && !loInclusive)));
        }
        
        boolean tooHigh(Object key, Comparator<? super K> cmp) {
            int c;
            return (hi != null && ((c = cpr(cmp, key, hi))>0 || (c == 0 && !hiInclusive)));
        }
        
        boolean inBounds(Object key, Comparator<? super K> cmp) {
            return !tooLow(key, cmp) && !tooHigh(key, cmp);
        }
        
        void checkKeyBounds(K key, Comparator<? super K> cmp) {
            if(key == null) {
                throw new NullPointerException();
            }
            
            if(!inBounds(key, cmp)) {
                throw new IllegalArgumentException("key out of range");
            }
        }
        
        /**
         * Returns true if node key is less than upper bound of range.
         */
        boolean isBeforeEnd(ConcurrentSkipListMap.Node<K, V> n, Comparator<? super K> cmp) {
            if(n == null) {
                return false;
            }
            
            if(hi == null) {
                return true;
            }
            
            K k = n.key;
            if(k == null) {// pass by markers and headers
                return true;
            }
            int c = cpr(cmp, k, hi);
            return c<0 || (c == 0 && hiInclusive);
        }
        
        /**
         * Returns lowest node. This node might not be in range, so
         * most usages need to check bounds.
         */
        ConcurrentSkipListMap.Node<K, V> loNode(Comparator<? super K> cmp) {
            if(lo == null) {
                return m.findFirst();
            } else if(loInclusive) {
                return m.findNear(lo, GT | EQ, cmp);
            } else {
                return m.findNear(lo, GT, cmp);
            }
        }
        
        /**
         * Returns highest node. This node might not be in range, so
         * most usages need to check bounds.
         */
        ConcurrentSkipListMap.Node<K, V> hiNode(Comparator<? super K> cmp) {
            if(hi == null) {
                return m.findLast();
            } else if(hiInclusive) {
                return m.findNear(hi, LT | EQ, cmp);
            } else {
                return m.findNear(hi, LT, cmp);
            }
        }
        
        /**
         * Returns lowest absolute key (ignoring directionality).
         */
        K lowestKey() {
            Comparator<? super K> cmp = m.comparator;
            ConcurrentSkipListMap.Node<K, V> n = loNode(cmp);
            if(isBeforeEnd(n, cmp)) {
                return n.key;
            } else {
                throw new NoSuchElementException();
            }
        }
        
        /**
         * Returns highest absolute key (ignoring directionality).
         */
        K highestKey() {
            Comparator<? super K> cmp = m.comparator;
            ConcurrentSkipListMap.Node<K, V> n = hiNode(cmp);
            if(n != null) {
                K last = n.key;
                if(inBounds(last, cmp)) {
                    return last;
                }
            }
            
            throw new NoSuchElementException();
        }
        
        Map.Entry<K, V> lowestEntry() {
            Comparator<? super K> cmp = m.comparator;
            for(; ; ) {
                ConcurrentSkipListMap.Node<K, V> n;
                V v;
                if((n = loNode(cmp)) == null || !isBeforeEnd(n, cmp)) {
                    return null;
                } else if((v = n.val) != null) {
                    return new SimpleImmutableEntry<K, V>(n.key, v);
                }
            }
        }
        
        Map.Entry<K, V> highestEntry() {
            Comparator<? super K> cmp = m.comparator;
            for(; ; ) {
                ConcurrentSkipListMap.Node<K, V> n;
                V v;
                if((n = hiNode(cmp)) == null || !inBounds(n.key, cmp)) {
                    return null;
                } else if((v = n.val) != null) {
                    return new SimpleImmutableEntry<K, V>(n.key, v);
                }
            }
        }
        
        Map.Entry<K, V> removeLowest() {
            Comparator<? super K> cmp = m.comparator;
            for(; ; ) {
                ConcurrentSkipListMap.Node<K, V> n;
                K k;
                V v;
                if((n = loNode(cmp)) == null) {
                    return null;
                } else if(!inBounds((k = n.key), cmp)) {
                    return null;
                } else if((v = m.doRemove(k, null)) != null) {
                    return new SimpleImmutableEntry<K, V>(k, v);
                }
            }
        }
        
        Map.Entry<K, V> removeHighest() {
            Comparator<? super K> cmp = m.comparator;
            for(; ; ) {
                ConcurrentSkipListMap.Node<K, V> n;
                K k;
                V v;
                if((n = hiNode(cmp)) == null) {
                    return null;
                } else if(!inBounds((k = n.key), cmp)) {
                    return null;
                } else if((v = m.doRemove(k, null)) != null) {
                    return new SimpleImmutableEntry<K, V>(k, v);
                }
            }
        }
        
        /**
         * Submap version of ConcurrentSkipListMap.findNearEntry.
         */
        Map.Entry<K, V> getNearEntry(K key, int rel) {
            Comparator<? super K> cmp = m.comparator;
            
            // adjust relation for direction
            if(isDescending) {
                if((rel & LT) == 0) {
                    rel |= LT;
                } else {
                    rel &= ~LT;
                }
            }
            
            if(tooLow(key, cmp)) {
                return ((rel & LT) != 0) ? null : lowestEntry();
            }
            
            if(tooHigh(key, cmp)) {
                return ((rel & LT) != 0) ? highestEntry() : null;
            }
            
            AbstractMap.SimpleImmutableEntry<K, V> e = m.findNearEntry(key, rel, cmp);
            
            if(e == null || !inBounds(e.getKey(), cmp)) {
                return null;
            } else {
                return e;
            }
        }
        
        // Almost the same as getNearEntry, except for keys
        K getNearKey(K key, int rel) {
            Comparator<? super K> cmp = m.comparator;
            
            // adjust relation for direction
            if(isDescending) {
                if((rel & LT) == 0) {
                    rel |= LT;
                } else {
                    rel &= ~LT;
                }
            }
            
            if(tooLow(key, cmp)) {
                if((rel & LT) == 0) {
                    ConcurrentSkipListMap.Node<K, V> n = loNode(cmp);
                    if(isBeforeEnd(n, cmp)) {
                        return n.key;
                    }
                }
                return null;
            }
            
            if(tooHigh(key, cmp)) {
                if((rel & LT) != 0) {
                    ConcurrentSkipListMap.Node<K, V> n = hiNode(cmp);
                    if(n != null) {
                        K last = n.key;
                        if(inBounds(last, cmp)) {
                            return last;
                        }
                    }
                }
                return null;
            }
            
            for(; ; ) {
                Node<K, V> n = m.findNear(key, rel, cmp);
                if(n == null || !inBounds(n.key, cmp)) {
                    return null;
                }
                
                if(n.val != null) {
                    return n.key;
                }
            }
        }
        
        /**
         * Variant of main Iter class to traverse through submaps.
         * Also serves as back-up Spliterator for views.
         */
        abstract class SubMapIter<T> implements Iterator<T>, Spliterator<T> {
            /** the last node returned by next() */
            Node<K, V> lastReturned;
            
            /** the next node to return from next(); */
            Node<K, V> next;
            
            /** Cache of next value field to maintain weak consistency */
            V nextValue;
            
            SubMapIter() {
                VarHandle.acquireFence();
                
                Comparator<? super K> cmp = m.comparator;
                for(; ; ) {
                    next = isDescending ? hiNode(cmp) : loNode(cmp);
                    if(next == null) {
                        break;
                    }
                    
                    V x = next.val;
                    if(x != null) {
                        if(!inBounds(next.key, cmp)) {
                            next = null;
                        } else {
                            nextValue = x;
                        }
                        
                        break;
                    }
                }
            }
            
            public final boolean hasNext() {
                return next != null;
            }
            
            public void remove() {
                Node<K, V> l = lastReturned;
                if(l == null) {
                    throw new IllegalStateException();
                }
                m.remove(l.key);
                lastReturned = null;
            }
            
            public Spliterator<T> trySplit() {
                return null;
            }
            
            public boolean tryAdvance(Consumer<? super T> action) {
                if(hasNext()) {
                    action.accept(next());
                    return true;
                }
                return false;
            }
            
            public void forEachRemaining(Consumer<? super T> action) {
                while(hasNext()) {
                    action.accept(next());
                }
            }
            
            public long estimateSize() {
                return Long.MAX_VALUE;
            }
            
            final void advance() {
                if(next == null) {
                    throw new NoSuchElementException();
                }
                
                lastReturned = next;
                
                if(isDescending) {
                    descend();
                } else {
                    ascend();
                }
            }
            
            private void ascend() {
                Comparator<? super K> cmp = m.comparator;
                for(; ; ) {
                    next = next.next;
                    if(next == null) {
                        break;
                    }
                    
                    V x = next.val;
                    if(x != null) {
                        if(tooHigh(next.key, cmp)) {
                            next = null;
                        } else {
                            nextValue = x;
                        }
                        
                        break;
                    }
                }
            }
            
            private void descend() {
                Comparator<? super K> cmp = m.comparator;
                for(; ; ) {
                    next = m.findNear(lastReturned.key, LT, cmp);
                    if(next == null) {
                        break;
                    }
                    
                    V x = next.val;
                    if(x != null) {
                        if(tooLow(next.key, cmp)) {
                            next = null;
                        } else {
                            nextValue = x;
                        }
                        
                        break;
                    }
                }
            }
            
        }
        
        final class SubMapValueIterator extends SubMapIter<V> {
            public V next() {
                V v = nextValue;
                advance();
                return v;
            }
            
            public int characteristics() {
                return 0;
            }
        }
        
        final class SubMapKeyIterator extends SubMapIter<K> {
            public final Comparator<? super K> getComparator() {
                return SubMap.this.comparator();
            }
            
            public K next() {
                Node<K, V> n = next;
                advance();
                return n.key;
            }
            
            public int characteristics() {
                return Spliterator.DISTINCT | Spliterator.ORDERED | Spliterator.SORTED;
            }
        }
        
        final class SubMapEntryIterator extends SubMapIter<Map.Entry<K, V>> {
            public Map.Entry<K, V> next() {
                Node<K, V> n = next;
                V v = nextValue;
                advance();
                return new AbstractMap.SimpleImmutableEntry<K, V>(n.key, v);
            }
            
            public int characteristics() {
                return Spliterator.DISTINCT;
            }
        }
    }
    
}
