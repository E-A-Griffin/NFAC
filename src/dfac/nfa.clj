(ns automata.nfa
  (:require [clojure.core.async
             :as async
             :refer [>! <! >!! <!!
                     go chan buffer close!
                     thread]]))

(def nfa-state (atom {:start-node nil
                      :nodes nil}))

(def nfa-chan
  "This atom contains the async channel that will hold the result of the string test"
  (atom (chan (async/sliding-buffer 1))))

(defn node
  "This function returns a map representing a node. The map is composed of
  the following keys:
      :label | STRING | Contains the label representing the node
      :start? | BOOL | Represents whether or not the node is a start state
      :final? | BOOL | Represents whether or not the node is a final state
      :transitions | VECTOR | Contains all of the transitions originating from this node."
  ([label]
   {:label label
    :start? false
    :final? false
    :transitions []})
  ([label start? final? transitions]
   {:label label
    :start? start?
    :final? final?
    :transitions transitions})
  ([label transitions]
   {:label label
    :start? false
    :final? false
    :transitions transitions}))

(defn get-state-node
  "Returns the node map corresponding to the label parameter from the nfa-state atom."
  [label]
  (get-in @nfa-state [:nodes (keyword label)]))

(defn response
  "This function returns a map representing the response from the asynchronous
  evaluation of the NFA. A response will either return true with the final state that the string evaluated to,
  or it will return false with the root node.
  The map is composed of the following key-value pairs:
      :final-node | CLJ-KEYWORD | This parameter contains the key corresponding to the final node that was reached when evaluating the string.
      :result | BOOL | This will contain either true or false, which is determined by whether or not an input string is valid for the NFA."
  [result node]
  {:final-node (keyword (:label node))
   :result result})



(defn test-string-r
  "This function is a recursive and multi-threaded implementation of a NFA/NFA traversal algorithm. It accepts an input string (in-str) and a node (node), and tests whether or not the transitions originating from that node will accept the in-str value. If it does, then the evaluation is placed on another thread, along with all of the other valid transitions originating from this node, and evaluated recursively until either no result is found, or the string tests true, at which point the async channel closes and no further data can be pushed into it."
  [in-str node]
  (let [c (first in-str)
        str-rest (clojure.string/join (rest in-str))
        cur-node-label (:label node)
        transitions (:transitions node)
        valid-transitions (filter (fn [t]
                                         (and (not (empty? in-str))
                                              (or (= (:accepts t) c)
                                                  (= (:accepts t) "lambda")))) transitions)]
    
    (cond (empty? in-str) ;if the string is empty,
          (if (= (:final? node) true) ;check to see if the current node is final
            (do (>!! @nfa-chan (response true node)) ;if it is, Wonderful! Put a response into the channel
                (close! @nfa-chan))                  ;and close it.
            (>!! @nfa-chan (response false node))) ;Otherwise, it's not valid.

          (empty? valid-transitions) ;if there are no valid transitions but the string still has characters to test,
          (>!! @nfa-chan (response false node)) ;return a false response.

          (not (empty? valid-transitions))      ;If there ARE valid transitions
          (doseq [transition valid-transitions]
            (cond (= (:accepts transition) "lambda")
                  (go (test-string-r in-str (get-state-node (:end-state transition))))
                  :else
                  (go (test-string-r str-rest (get-state-node (:end-state transition)))))))))

(defn test-string
  "This is an abstracted version of the recursive function that makes it easier to call
  multiple or new tests."
  [in-str node-label]
  (let [node (get-state-node node-label)]
    (reset! nfa-chan (chan (async/sliding-buffer 1)))
    (test-string-r in-str node)
    (let [result (<!! @nfa-chan)]
      result)))

(defn transition
  "This function returns a map that represents a transition. It is composed of the following
  key-value pairs:
      :accepts | STRING or CHAR | Contains the character (or string) that will be accepted by the next node.
      :begin-state | STRING | contains the label for the origin node
      :end-state | STRING | contains the label for the destination node"

  ([begin-state end-state]
   {:accepts "lambda"
    :begin-state begin-state
    :end-state end-state})
  ([accepts begin-state end-state]
   {:accepts accepts
    :begin-state begin-state
    :end-state end-state}))

(defn add-node
  "This function adds a node to the state"
  [node]
  (let [nodes (:nodes @nfa-state)]
    (if (= (:start? node) true) ;if the given node is the start node,
      (swap! nfa-state assoc :start-node (keyword (:label node))) ;let's save that info
      nil) ;otherwise do nothing
    (swap! nfa-state assoc :nodes (merge nodes {(keyword (:label node)) ;Insert the node
                                                         node}))))

(defn add-nodes
  "This function adds multiple nodes to the state"
  [& nodes]
  (doseq [node nodes]
    (add-node node)))

(defn delete-node
  "This function deletes a node from the state"
  [label]
  (let [key (keyword label)
        node (get-in @nfa-state [:nodes key])]
    (if-not (= node nil)
      (do
        (swap! nfa-state assoc :nodes (into {} (remove (fn [[k e]]
                                                         (if (= k key)
                                                           (do
                                                             (if (= k (:start-node @nfa-state))
                                                               (swap! nfa-state assoc :start-node nil))
                                                             true)
                                                           false))
                                                       (:nodes @nfa-state))))))))

(defn delete-node [& labels]
  (doseq [label labels]
    (delete-node label)))

(defn add-transition
  "This function adds a transition to the NFA by inserting the transition map into the
  begin-state node defined by the transition map's \"begin-state\" key-val pair."
  [transition]
  (let [begin-state (:begin-state transition)
        state @nfa-state
        begin-node (get (:nodes state) (keyword begin-state))
        transitions (distinct (conj (:transitions begin-node)
                                    transition))]
    (swap! nfa-state assoc :nodes (merge (:nodes state)
                                         {(keyword begin-state)
                                          (node begin-state
                                                (:start? begin-node)
                                                (:final? begin-node)
                                                (vec transitions))}))))

(defn remove-transition
  "This function removes a transition from the NFA by removing the transition map from the node defined by the \"begin-state\" key-val pair."
  [transition]
  (let [node-label (:begin-state transition)
        node (get-in [:nodes (keyword node-label)] @nfa-state)]
    (swap! nfa-state assoc-in [:nodes (keyword node-label) :transitions] (vec (remove #(= transition)
                                                                                  (:transitions node))))))

(defn clear-state
  "This function resets the state to its initial empty state, where all keys in the map have a value of nil"
  []
  (reset! nfa-state {:start-node nil
                     :nodes nil}))

(defn load-state
  "This function loads a map into the state.
  WARNING: DOES NOT PERFORM ANY CHECKING TO ENSURE THAT THE GIVEN MAP IS IN
  THE PROPER FORMAT"
  [state-map]
  (reset! nfa-state state-map))

(defn test-load-state []
  (doseq [node (list (node "q1" true false [])
                     (node "q2")
                     (node "q3")
                     (node "q4")
                     (node "q5")
                     (node "q6")
                     (node "q7")
                     (node "q8" false true []))]
    (add-node node))
  (doseq [transition (list (transition \a "q1" "q2")
                           (transition \b "q2" "q3")
                           (transition \c "q3" "q4")
                           (transition \d "q4" "q8")
                           (transition \e "q1" "q5")
                           (transition \f "q5" "q6")
                           (transition \g "q6" "q7")
                           (transition \h "q7" "q8"))]
    (add-transition transition)))
