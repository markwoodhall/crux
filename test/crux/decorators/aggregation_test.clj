(ns crux.decorators.aggregation-test
  (:require [clojure.test :as t]
            [crux.api :as api]
            [crux.decorators.aggregation.alpha :as aggr]
            [crux.fixtures :as f]
            [crux.fixtures.bootstrap :as fb :refer [*api*]]))

(t/use-fixtures :each fb/with-standalone-system)

(t/deftest test-count-aggregation
  (f/transact!
    *api*
    [{:crux.db/id :a1 :user/name "patrik" :user/post 1 :post/cost 30}
     {:crux.db/id :a2 :user/name "patrik" :user/post 2 :post/cost 35}
     {:crux.db/id :a3 :user/name "patrik" :user/post 3 :post/cost 5}
     {:crux.db/id :a4 :user/name "niclas" :user/post 1 :post/cost 8}])

  (t/testing "with vector syntax"
    (t/is (= [{:user-name "niclas" :post-count 1 :cost-sum 8}
              {:user-name "patrik" :post-count 3 :cost-sum 70}]
             (aggr/q
               (api/db *api*)
               '{:aggr {:partition-by [?user-name]
                        :select
                        {?cost-sum [0 (+ acc ?post-cost)]
                         ?post-count [0 (inc acc) ?e]}}
                 :where [[?e :user/name ?user-name]
                         [?e :post/cost ?post-cost]]}))))

  (t/testing "with reducer function syntax"
    (t/is (= [{:user-name "niclas" :post-count 1 :cost-sum 8}
              {:user-name "patrik" :post-count 3 :cost-sum 70}]
             (aggr/q
               (api/db *api*)
               '{:aggr {:partition-by [?user-name]
                        :select
                        {?cost-sum (+ ?post-cost)
                         ?post-count [0 (inc acc) ?e]}}
                 :where [[?e :user/name ?user-name]
                         [?e :post/cost ?post-cost]]}))))

  (t/testing "not doing anything to a query without aggr clause"
    (t/is (= #{[:a4 "niclas" 8]
               [:a3 "patrik" 5]
               [:a2 "patrik" 35]
               [:a1 "patrik" 30]}
             (aggr/q
               (api/db *api*)
               '{:find [?e ?user-name ?post-cost]
                 :where [[?e :user/name ?user-name]
                         [?e :post/cost ?post-cost]]})))))


(t/deftest test-with-decorator
  (f/transact!
   *api*
    [{:crux.db/id :a1 :user/name "patrik" :user/post 1 :post/cost 30}
     {:crux.db/id :a2 :user/name "patrik" :user/post 2 :post/cost 35}
     {:crux.db/id :a3 :user/name "patrik" :user/post 3 :post/cost 5}
     {:crux.db/id :a4 :user/name "niclas" :user/post 1 :post/cost 8}])

  (let [decorated (aggr/aggregation-decorator *api*)]
    (t/is (= [{:user-name "niclas" :post-count 1 :cost-sum 8}
              {:user-name "patrik" :post-count 3 :cost-sum 70}]
             (api/q
               (api/db decorated)
               '{:aggr {:partition-by [?user-name]
                        :select
                        {?cost-sum (+ ?post-cost)
                         ?post-count [0 (inc acc) ?e]}}
                 :where [[?e :user/name ?user-name]
                         [?e :post/cost ?post-cost]]})))))
