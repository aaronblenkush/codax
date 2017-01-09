(ns codax.address-store
  (:require
   [clojure.core.cache :as cache]
   [clojure.pprint :refer [pprint]]
   [clojure.java.io :as io]
   [taoensso.nippy :as nippy]
   [clojure.string :as str]
   [vijual :refer [draw-tree]])
  (:import
   [java.io RandomAccessFile FileOutputStream]
   [java.nio ByteBuffer]
   [java.nio.file Files Paths StandardOpenOption]
   [java.nio.channels FileChannel]))

;;;;; Settings

(def order 32)
(def nippy-options {:compressor nippy/lz4-compressor})

;;;;; Databases

(def file-type-tag (long 14404350))
(def file-version-tag (int 1))
(defonce open-databases (atom {}))

(defn- to-path [str]
  (Paths/get str (make-array String 0)))

(defn- read-manifest-buffer [^ByteBuffer buf]
  (.position buf 16)
  (loop [root-id 1
         id-counter 1
         manifest (transient {})]
    (if (.hasRemaining buf)
      (let [id (.getLong buf)
            new-id-counter (max id id-counter)]
        (if (= 0 id)
          (recur (.getLong buf) new-id-counter manifest)
          (recur root-id new-id-counter (assoc! manifest id (.getLong buf)))))
      {:root-id root-id
       :id-counter id-counter
       :manifest (persistent! manifest)})))

(defn- load-manifest [path]
  (let [dir (io/as-file path)]
    (when (and (.exists dir) (not (.isDirectory dir)))
      (throw (Exception. (str path " already exists. Invalid Database."))))
    (when (not (.exists dir))
      (io/make-parents (str path "/files")))
    (let [manifest-file (RandomAccessFile. (str path "/manifest") "rw")]
      (try
        ;; check or write file-type-tag and file-version-tag
        (if (pos? (.length manifest-file))
          (let [file-type-long (.readLong manifest-file)
                file-version-int (.readInt manifest-file)
                order-int (.readInt manifest-file)]
            (do
              (when (not (= file-type-long file-type-tag))
                (throw (Exception. "Invalid Database.")))
              (when (not (= file-version-int file-version-tag))
                (throw (Exception. "Incompatible Version.")))
              (when (not (= order-int order))
                (throw (Exception. (str "Order Mismatch. Current Order: " order " File Order: " order-int))))))
          (do
            (.writeLong manifest-file file-type-tag)
            (.writeInt manifest-file file-version-tag)
            (.writeInt manifest-file order)))
        (finally (.close manifest-file)))
      (read-manifest-buffer (ByteBuffer/wrap (Files/readAllBytes (to-path (str path "/manifest"))))))))

(defn- load-nodes-offset [path]
  (let [node-file (RandomAccessFile. (str path "/nodes") "rw")]
    (try
      (.length node-file)
      (finally (.close node-file)))))

(defn close-database [path-or-db]
  (let [path (if (string? path-or-db)
               path-or-db
               (:path path-or-db))]
    (when-let [open-db (@open-databases path)]
      (reset! (:data open-db) false)
      (.close (:manifest-channel open-db))
      (.close (:nodes-channel open-db))
      (.close (:file-reader open-db))
      (swap! open-databases dissoc path open-db))))

(defn open-database [path]
  (if-let [existing-db (@open-databases path)]
    (do (println "db already open" path)
        (close-database path)
        (open-database path))
    (let [{:keys [root-id id-counter manifest]} (load-manifest path)
          nodes-offset (load-nodes-offset path)
          db {:path path
              :write-lock (Object.)
              :manifest-channel (FileChannel/open (to-path (str path "/manifest")) (into-array [StandardOpenOption/APPEND
                                                                                                StandardOpenOption/SYNC]))
              :nodes-channel (FileChannel/open (to-path (str path "/nodes")) (into-array [StandardOpenOption/APPEND
                                                                                          StandardOpenOption/SYNC]))
              :file-reader (RandomAccessFile. (str path "/nodes") "r")
              :data (atom {:manifest manifest
                           :cache (cache/lru-cache-factory {} :threshold 32)
                           :root-id root-id
                           :id-counter id-counter
                           :nodes-offset nodes-offset})}]
      (swap! open-databases assoc path db)
      db)))

;;;;; Transactions

(defn- update-database! [{:keys [db root-id id-counter manifest]} nodes-offset manifest-delta nodes-by-address]
  (swap! (:data db)
         (fn [data]
           (let [updated-cache (reduce-kv (fn [c address node]
                                            (let [old-address (manifest (:id node))
                                                  c (cache/evict c old-address)]
                                              (if (not (nil? node))
                                                (cache/miss c address node)
                                                c)))
                                          (:cache data)
                                          nodes-by-address)]
             (-> data
                 (assoc :root-id root-id
                        :cache updated-cache
                        :id-counter id-counter
                        :nodes-offset nodes-offset)
                 (update :manifest merge manifest-delta))))))

(defn- save-buffers! [db ^ByteBuffer manifest-buffer ^ByteBuffer nodes-buffer]
  (let [^FileChannel manifest-channel (:manifest-channel db)
        ^FileChannel nodes-channel (:nodes-channel db)]
    (.write manifest-channel ^ByteBuffer (.flip manifest-buffer))
    (.write nodes-channel ^ByteBuffer (.flip nodes-buffer))))

(defn commit! [txn]
  (let [manifest-buffer (ByteBuffer/allocate (* 16 (inc (count (:dirty-nodes txn)))))]
    (loop [remaining-nodes (:dirty-nodes txn)
           address (:nodes-offset txn)
           manifest-delta {}
           node-buffers []
           nodes-by-address {}
           total-length 0]
      (if (empty? remaining-nodes)
        (let [all-nodes-buffer (ByteBuffer/allocate (+ 8 total-length))]
          (doseq [^ByteBuffer b node-buffers]
            (.put all-nodes-buffer (.array b)))
          (.putLong all-nodes-buffer (long 0))
          (.putLong manifest-buffer (long 0))
          (.putLong manifest-buffer (long (:root-id txn)))
          (save-buffers! (:db txn) manifest-buffer all-nodes-buffer)
          (update-database! txn (+ 8 address) manifest-delta nodes-by-address))
        (let [[id node] (first remaining-nodes)
              ^bytes encoded-value (nippy/freeze node nippy-options)
              size (count encoded-value)
              buf (ByteBuffer/allocate (+ 8 size))]
          (.putLong buf (long size))
          (.put buf encoded-value)
          (.putLong manifest-buffer (long id))
          (.putLong manifest-buffer (long address))
          (recur (rest remaining-nodes)
                 (+ 8 address size)
                 (assoc manifest-delta id address)
                 (conj node-buffers buf)
                 (assoc nodes-by-address address node)
                 (+ 8 size total-length)))))))

(defn make-transaction [database]
  (let [{:keys [manifest root-id id-counter nodes-offset]} @(:data database)]
    {:db database
     :root-id root-id
     :id-counter id-counter
     :nodes-offset nodes-offset
     :manifest manifest
     :dirty-nodes {}}))

;;; Macros

(defmacro with-write-transaction [[database tx-symbol] & body]
  `(let [db# ~database]
     (locking (:write-lock db#)
       (let [~tx-symbol (make-transaction db#)]
         (commit! (do ~@body))))))

(defmacro with-read-transaction [[database tx-symbol] & body]
  `(let [db# ~database]
     (let [~tx-symbol (make-transaction db#)]
       ~@body)))

;;; Node Fetching

(defn- read-node-from-file [db address]
  (let [file ^RandomAccessFile (:file-reader db)];(RandomAccessFile. (str (:path db) "/nodes") "r")]
    (locking file
      (.seek file address)
      (let [size (.readLong file)
            data (byte-array size)]
        (.read file data)
        (nippy/thaw data nippy-options)))))

(def cache-misses (atom 0))
(def cache-hits (atom 0))

(defn- load-node [{:keys [db manifest]} id]
  (let [address (manifest id)]
    (if (nil? address)
      {:type :leaf
       :id 1
       :records (sorted-map)}
                                        ;      (read-node-from-file db address))))
        (let [cache (:cache @(:data db))]
          (if (cache/has? cache address)
            (do
              (swap! (:data db) assoc :cache (cache/hit cache address))
              (swap! cache-hits inc)
              (cache/lookup cache address))
            (let [loaded-node (read-node-from-file db address)]
              (swap! (:data db) assoc :cache (cache/miss cache address loaded-node))
              (swap! cache-misses inc)
              loaded-node))))))

(defn get-node [txn id]
  (or
   ((:dirty-nodes txn) id)
   (load-node txn id)))

;;;;; B+Tree

(defn leaf-node? [node]
  (= :leaf (:type node)))

(defn- get-matching-child [records k]
  (first (rsubseq records <= k)))

;;; Get

(defn get-matching-leaf [txn {:keys [records] :as node} k]
  (if (leaf-node? node)
    node
    (recur txn
           (get-node txn (second (get-matching-child records k)))
           k)))

(defn b+get [txn k]
  (let [root-id (:root-id txn)
        root (get-node txn (:root-id txn))
        leaf (if (leaf-node? root)
               root
               (get-matching-leaf txn root k))]
    ((:records leaf) k)))

;;; Seek


(defn b+seek [txn start end & {:keys [limit]}]
  (let [root (get-node txn (:root-id txn))
        start-node (get-matching-leaf txn root start)
        end-node (get-matching-leaf txn root end)
        results (if (= (:id start-node) (:id end-node))
                  (vec (subseq (:records start-node) >= start <= end))
                  (persistent!
                   (loop [pairs (transient (vec (subseq (:records start-node) >= start)))
                          next-id (:next start-node)]
                     (cond
                       (nil? next) pairs
                       (and limit (>= (count pairs) limit)) pairs
                       (= next-id (:id end-node)) (reduce conj! pairs (subseq (:records end-node) <= end))
                       :else (let [next-node (get-node txn next-id)]
                               (recur
                                (reduce conj! pairs (:records next-node))
                                (:next next-node)))))))]
    (if (and limit (> (count results) limit))
      (subvec results 0 limit)
      results)))

;;; Insert

(defn split-records [txn {:keys [id type records] :as node}]
  (let [split-pos (Math/ceil (/ (count records) 2))
        left-records (take split-pos records)
        right-records (drop split-pos records)
        split-key (first (first right-records))
        right-records (if (= type :internal)
                        (assoc-in (vec right-records) [0 0] nil)
                        right-records)
        left-node (assoc node :records (into (sorted-map) left-records))
        left-id id
        txn (update txn :id-counter inc)
        right-id (:id-counter txn)
        right-node {:id right-id :type type :records (into (sorted-map) right-records)}
        right-node (if (leaf-node? left-node)
                     (assoc right-node :next (:next left-node))
                     right-node)
        left-node (if (leaf-node? left-node)
                    (assoc left-node :next right-id)
                    left-node)]
    {:txn (-> txn
              (assoc-in [:dirty-nodes left-id] left-node)
              (assoc-in [:dirty-nodes right-id] right-node))
     :split-key split-key
     :left-id left-id
     :right-id right-id}))

(defn- insert-leaf [txn {:keys [id records] :as node} k v]
  (let [new-records (assoc records k v)
        updated-node (assoc node :records new-records)]
    (if (> order (count new-records))
      (assoc-in txn [:dirty-nodes id] updated-node)
      (split-records txn updated-node))))

(defn handle-split-node [{:keys [txn split-key left-id right-id]} {:keys [id records] :as node}]
  (let [new-records (assoc records split-key right-id)
        updated-node (assoc node :records new-records)]
    (if (>= order (count new-records))
      (assoc-in txn [:dirty-nodes id] updated-node)
      (split-records txn updated-node))))

(defn- insert-internal [txn {:keys [id records] :as node} k v]
  (let [[child-key child-id] (get-matching-child records k)
        child (get-node txn child-id)
        result (if (leaf-node? child)
                 (insert-leaf txn child k v)
                 (insert-internal txn child k v))]
    (if (:split-key result)
      (handle-split-node result node)
      result)))

(defn- handle-split-root [{:keys [txn split-key left-id right-id]}]
  (let [txn (update txn :id-counter inc)
        root-id (:id-counter txn)
        txn (assoc txn :root-id root-id)
        new-node {:id root-id
                  :type :internal
                  :records (sorted-map nil left-id split-key right-id)}]
    (assoc-in txn [:dirty-nodes root-id] new-node)))

(defn b+insert [txn k v]
  (let [root (get-node txn (:root-id txn))
        result (if (leaf-node? root)
                 (insert-leaf txn root k v)
                 (insert-internal txn root k v))]
    (if (:split-key result)
      (handle-split-root result)
      result)))

;;; Remove

(defn- remove-leaf [txn {:keys [id records] :as node} k]
  (let [updated-records (dissoc records k)
        updated-node (assoc node :records updated-records)]
    (if (< (count updated-records) (int (/ order 2)))
      {:combine updated-node
       :txn txn}
      (assoc-in txn [:dirty-nodes id] updated-node))))

(defn- combine-records [mid-key left-node right-node]
  (if (leaf-node? left-node)
    (merge (:records left-node) (:records right-node))
    (let [right-records (:records right-node)
          right-records (assoc right-records mid-key (right-records nil))
          right-records (dissoc right-records nil)]
      (merge (:records left-node) right-records))))

(defn- distribute-records [txn mid-key left-node right-node]
  (let [combined-records (combine-records mid-key left-node right-node)
        split-pos (Math/ceil (/ (count combined-records) 2))
        left-records (take split-pos combined-records)
        right-records (drop split-pos combined-records)
        split-key (first (first right-records))
        right-records (if (leaf-node? right-node)
                        right-records
                        (assoc-in (vec right-records) [0 0] nil))]
    {:distributed-by split-key
     :mid-key mid-key
     :txn
     (-> txn
         (assoc-in [:dirty-nodes (:id left-node)] (assoc left-node :records (into (sorted-map) left-records)))
         (assoc-in [:dirty-nodes (:id right-node)] (assoc right-node :records (into (sorted-map) right-records))))}))


(defn- merge-nodes [txn mid-key left-node right-node]
  (let [combined-records (combine-records mid-key left-node right-node)
        updated-node (assoc left-node :records combined-records)
        updated-node (if (leaf-node? left-node)
                       (assoc updated-node :next (:next right-node))
                       updated-node)]
    {:merged true
     :mid-key mid-key
     :txn
     (-> txn
         (assoc-in [:dirty-nodes (:id updated-node)] updated-node)
         (assoc-in [:dirty-nodes (:id right-node)] nil))}))

(defn- get-siblings-helper [txn parent-records child-key]
  (let [rv (vec parent-records)
        focal-index (first (keep-indexed #(when (= (first %2) child-key) %1) rv))
        [left-key left-id] (if (< 0 focal-index) (nth rv (dec focal-index)))
        [right-key right-id] (if (< focal-index (dec (count rv))) (nth rv (inc focal-index)))]
    {:left-key left-key
     :left-sibling (when left-id (get-node txn left-id))
     :right-key right-key
     :right-sibling (when right-id (get-node txn right-id))}))

(defn- combine-children [txn {:keys [records]} child-key focal-child]
  (let [{:keys [left-key left-sibling right-key right-sibling]} (get-siblings-helper txn records child-key)
        left-count (count (:records left-sibling))
        right-count (count (:records right-sibling))
        min-count (int (/ order 2))]
    (cond
      (> right-count min-count) (distribute-records txn right-key focal-child right-sibling)
      (> left-count min-count) (distribute-records txn child-key left-sibling focal-child)
      (= right-count min-count) (merge-nodes txn right-key focal-child right-sibling)
      (= left-count min-count) (merge-nodes txn child-key left-sibling focal-child))))

(defn- remove-internal [txn {:keys [id records] :as node} k]
  (let [[child-key child-id] (get-matching-child records k)
        child (get-node txn child-id)
        result (if (leaf-node? child)
                 (remove-leaf txn child k)
                 (remove-internal txn child k))]
    (if-let [focal-child (:combine result)]
      (let [{:keys [mid-key distributed-by txn]} (combine-children (:txn result) node child-key focal-child)
            mid-value (get records mid-key)
            updated-records (dissoc records mid-key)
            updated-records (if distributed-by
                              (assoc updated-records distributed-by mid-value)
                              updated-records)
            updated-node (assoc node :records updated-records)]
        (if (< (count updated-records) (int (/ order 2)))
          {:combine updated-node
           :txn txn}
          (assoc-in txn [:dirty-nodes id] updated-node)))
      result)))

(defn b+remove [txn k]
  (let [root-id (:root-id txn)
        root (get-node txn (:root-id txn))
        result (if (leaf-node? root)
                 (remove-leaf txn root k)
                 (remove-internal txn root k))]
    (if (:combine result)
      (let [txn (:txn result)
            node (:combine result)]
        (if (= 1 (count (:records node)))
          (let [new-root-id (second (first (:records node)))]
            (-> txn
                (assoc :root-id new-root-id)
                (assoc-in [:dirty-nodes root-id] nil)))
          (assoc-in txn [:dirty-nodes (:id node)] node)))
      result)))

;;;;; Testing

(defn draw-b-tree-helper [txn {:keys [records] :as node}]
  (if (leaf-node? node)
    (vector (str (:id node)") " (str/join " " (keys records)) " (" (:next node)))
    (apply vector
           (cond
              (zero? (count (keys records))) "0!"
              (= 1 (count (keys records))) "_"
              :else (str/trim (str/join " :" (keys records))))
           (map (partial draw-b-tree-helper txn) (map (partial get-node txn) (vals records))))))

(defn draw-tx [txn]
  (let [root (get-node txn (:root-id txn))]
    (draw-tree [(draw-b-tree-helper txn root)])))

(defn draw-test [db add remove]
  (let [txn (make-transaction db)]
    (draw-tx txn)
    (let [txn (reduce (fn [tx n]
                        (let [t (b+insert tx n (str n))]
                          ;;(clojure.pprint/pprint (dissoc t :db))
                          (draw-tx t)
                          t))
                      txn add)]
      (reduce (fn [tx n]
                (let [t (b+remove tx n)]
                  (draw-tx t)
                  t))
              txn remove))))

(defn test-insert-and-remove [db node-count]
  (let [keys (range node-count)
        insertions  (shuffle keys)
        deletions (shuffle keys)
        _ (pprint "inserting")
        txn (loop [current-elements (list)
                   remaining-insertions insertions
                   txn (make-transaction db)]
              (assert (=
                   current-elements
                   (doall (map (partial b+get txn) current-elements))))
              (assert (let [all-keys (map first (b+seek txn -100 (inc node-count)))]
                        (if (<= 2 (count all-keys))
                          (apply < all-keys)
                          true)))
              (if (empty? remaining-insertions)
                txn
                (let [el (first remaining-insertions)]
                  (recur (conj current-elements el)
                         (rest remaining-insertions)
                         (b+insert txn el el)))))
        ;;_ (pprint (:dirty-nodes txn))
        ;;_ (draw-tx txn)
        _ (pprint "removing")
        txn (loop [remaining-deletions deletions
                   completed-deletions (list)
                   txn txn]
              (assert (=
                       remaining-deletions)
                      (doall (map (partial b+get txn) remaining-deletions)))
              (assert (let [all-keys (map first (b+seek txn -100 (inc node-count)))]
                        (if (<= 2 (count all-keys))
                          (apply < all-keys)
                          true)))
              (assert (every? nil? (doall (map (partial b+get txn) completed-deletions))))
              (if (empty? remaining-deletions)
                txn
                (let [el (first remaining-deletions)]
                  (recur (rest remaining-deletions)
                         (conj completed-deletions el)
                         (-> txn
                          (b+remove el)
                          (b+remove el))))))]
    (println "final dirty nodes")
    (pprint (:dirty-nodes txn))
    (draw-tx txn)))

(defn commitment-test [path]
  (let [db (open-database path)
        els (range 1000)
        insertions (shuffle els)]
    (with-write-transaction [db tx]
      (reduce (fn [txn el] (b+insert txn el el))
              tx
              insertions))
    (let [old-data @(:data db)
          db (open-database path)
          new-data @(:data db)]
      (assert (= old-data new-data))
      (pprint old-data)
      (pprint new-data))))

(defn stress-database []
  (reset! cache-hits 0)
  (reset! cache-misses 0)
  (let [db (open-database "data/crash-test-dummy-address-2")
        inc-count #(if (number? %)
                     (inc %)
                     1)
        writes (doall (map #(fn [] (with-write-transaction [db tx] (b+insert tx (str %) (str "v" %)))) (range 10000)))
        updates (repeat 10000 #(with-write-transaction [db tx] (b+insert tx "counter" (inc-count (b+get tx "counter")))))
        reads (repeat 10000 #(with-read-transaction [db tx] (b+get tx (str (int (rand 1000)) "x"))))
        seeks nil;(repeat 1000 #(with-read-transaction [db tx] (b+seek tx "0" "z")))
        ops (shuffle (concat writes reads updates seeks))]
    (try
      (dorun (pmap #(%) ops))
      (let [result (with-read-transaction [db tx]
                     (b+seek tx (str (char 0x00)) (str (char 0xff))))]
        (println "cache-size" (count (:cache @(:data db))))
        (println "cache hits" @cache-hits)
        (println "cache misses" @cache-misses)
        (println (count result))
        (println (last result)))
      (finally (close-database db)))))