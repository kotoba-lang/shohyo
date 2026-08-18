(ns kotoba.shohyo.jp-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [kotoba.shohyo.jp :as jp]))

(def ^:private profitable
  {:net-sales 1000 :cost-of-sales 600 :sga 200
   :non-operating-income 50 :non-operating-expense 30
   :extraordinary-income 10 :extraordinary-loss 5})

;; ---------------------------------------------------------------------------
;; the rule a naive implementation gets wrong
;; ---------------------------------------------------------------------------

(deftest a-negative-rung-is-a-loss-not-a-negative-profit
  (testing "第八十九条第二項: 零未満なら 零から減じて得た額を売上総損失金額と
            して表示 — a different label AND a sign flip, not -500 printed
            under 売上総利益"
    (let [r (jp/stage-profits (assoc profitable :cost-of-sales 1500))
          g (:shohyo.jp/gross r)]
      (is (= 500 (:amount g)) "positive magnitude, per 零から…を減じて得た額")
      (is (= "売上総損失金額" (:label g)))
      (is (= :loss (:sign g)))
      (is (= "第八十九条" (:article g))))))

(deftest every-rung-carries-the-same-rule
  (testing "第九十条・第九十一条・第九十二条 each repeat the second paragraph"
    (let [r (jp/stage-profits {:net-sales 100 :cost-of-sales 50 :sga 500
                               :non-operating-income 0 :non-operating-expense 0
                               :extraordinary-income 0 :extraordinary-loss 0})]
      (is (= :profit (:sign (:shohyo.jp/gross r))))
      (is (= "営業損失金額" (:label (:shohyo.jp/operating r))))
      (is (= "経常損失金額" (:label (:shohyo.jp/ordinary r))))
      (is (= "税引前当期純損失金額" (:label (:shohyo.jp/pretax r))))
      (is (= 450 (:amount (:shohyo.jp/operating r)))))))

(deftest zero-is-a-profit-not-a-loss
  (testing "零未満 is the condition; zero itself is not below zero"
    (let [r (jp/stage-profits (assoc profitable :cost-of-sales 1000))]
      (is (= 0 (:amount (:shohyo.jp/gross r))))
      (is (= "売上総利益金額" (:label (:shohyo.jp/gross r)))))))

;; ---------------------------------------------------------------------------
;; the ladder
;; ---------------------------------------------------------------------------

(deftest the-ladder-follows-the-articles
  (let [r (jp/stage-profits profitable)]
    (is (= :checked (:shohyo.jp/coverage r)))
    (is (= 400 (:amount (:shohyo.jp/gross r))) "1000 - 600")
    (is (= 200 (:amount (:shohyo.jp/operating r))) "400 - 200")
    (is (= 220 (:amount (:shohyo.jp/ordinary r))) "200 + 50 - 30")
    (is (= 225 (:amount (:shohyo.jp/pretax r))) "220 + 10 - 5")))

(deftest the-concepts-are-kanjos-and-are-signed
  (testing "so an internally-produced ladder lines up with an externally-read
            filing; the concept map keeps the sign the label discards"
    (let [r (jp/stage-profits (assoc profitable :cost-of-sales 1500))]
      (is (= -500 (get (:shohyo.jp/concepts r) "gross-profit")))
      (is (= #{"gross-profit" "operating-income" "ordinary-income" "pretax-income"}
             (set (keys (:shohyo.jp/concepts r))))))))

(deftest a-missing-section-is-not-zero
  (testing "a missing 売上原価 is an unstated cost, not a zero one — treating
            it as zero would report a gross profit equal to sales"
    (let [r (jp/stage-profits (dissoc profitable :cost-of-sales))]
      (is (= :not-declared (:shohyo.jp/coverage r)))
      (is (= [:cost-of-sales] (:shohyo.jp/missing-sections r)))
      (is (not (contains? r :shohyo.jp/gross)))))
  (testing "and it names every one that is missing, not just the first"
    (is (= [:sga :extraordinary-loss]
           (:shohyo.jp/missing-sections
            (jp/stage-profits (dissoc profitable :sga :extraordinary-loss)))))))

;; ---------------------------------------------------------------------------
;; the sections
;; ---------------------------------------------------------------------------

(deftest the-seven-pl-sections-are-the-articles-seven
  (is (= 7 (count jp/pl-sections)))
  (is (= ["売上高" "売上原価" "販売費及び一般管理費" "営業外収益"
          "営業外費用" "特別利益" "特別損失"]
         (mapv :jp (sort-by :order (vals jp/pl-sections))))))

(deftest fixed-assets-contain-three-rather-than-being-a-peer
  (testing "第七十四条第二項 subdivides 固定資産; flattening them into peers
            would make the 資産の部 have five sections instead of three"
    (is (= 3 (count (filter #(= :fixed-assets (:parent %)) (vals jp/bs-sections)))))
    (is (= 3 (count (filter #(and (= :asset (:type %)) (nil? (:parent %)))
                            (vals jp/bs-sections)))))))

(deftest a-section-that-contradicts-the-article-is-caught
  (testing "tagging 売上原価 as revenue puts it on the wrong side of
            第八十九条 and the gross figure comes out looking right"
    (let [p (jp/section-problems {"cogs" {:section :cost-of-sales :type :revenue}})]
      (is (= [:section-type-conflict] (mapv :problem p)))
      (is (str/includes? (:detail (first p)) "expense"))))
  (is (empty? (jp/section-problems {"cogs" {:section :cost-of-sales :type :expense}})))
  (is (empty? (jp/section-problems {"x" {:type :asset}})) "no section is not a problem")
  (is (= [:unknown-section]
         (mapv :problem (jp/section-problems {"x" {:section :made-up :type :asset}})))))

;; ---------------------------------------------------------------------------
;; provenance
;; ---------------------------------------------------------------------------

(deftest every-implemented-article-is-quoted
  (let [p jp/provisions]
    (is (= "418M60000010013" (:law/id p)))
    (is (= "2026-08-18" (:retrieved-at p)))
    (is (= 8 (count (:articles p))))
    (doseq [{:keys [article quote]} (:articles p)]
      (is (str/starts-with? article "第") (str article))
      (is (> (count quote) 30) (str article " quote is too short to be the text")))
    (testing "the ladder articles quote the second paragraph, which is the
              part a naive implementation drops"
      (doseq [a ["第八十九条" "第九十条" "第九十一条" "第九十二条"]]
        (let [q (:quote (first (filter #(= a (:article %)) (:articles p))))]
          (is (str/includes? q "零未満") (str a " omits the 零未満 clause")))))))
