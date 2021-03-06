(ns envoys-backend.core
  (:require [promesa.core :as p]
            [cljs-lambda.macros :refer-macros [deflambda defgateway]]
            [cljs-lambda.local :as local]
            [cljs-lambda.aws.event :as awse]
            ["aws-sdk" :as aws]
            ["util" :as util]))

(defn wrap-layout [hmap status]
  "Wrap the passed-in body map with the other parts of the json map
   that we want to return."
  (let [context {:status status
                 :headers {:content-type "application/json"
                           :access-control-allow-origin "*"
                           :access-control-allow-credentials "true"}}]
    (-> hmap
        (merge context))))

(defn wrap-common [hmap]
  "UI components common to all endpoints"
  (-> hmap
      (merge {:title "Envoys | Software Engineering"})))

;; refactor this into defmulti when we move to database
(def index-data
  {:hero-text "A functional-first engineering collective that does things right, first time."})

(def about-data
  {:hero-text "We specialise in green-field and rapid prototyping, from static MVPs to full-stack apps and data systems. Whether it's engineering, devops or business development, we can help with all areas of the project lifecycle."})

(def blog-data
  {:hero-text "Under construction..."})

(def contact-data
  {:hero-text "For general queries, drop us a line at hello@envoys.io. To discuss a project with our CTO, email alex@lynh.am"})

(defn clj->json [hmap]
  (let [js-map (-> hmap
                   clj->js)]
    (.stringify js/JSON js-map)))

(defn body->layout-as-json [data-map]
  (let [wrapped-map (-> data-map
                        wrap-common)]
    (clj->json wrapped-map)))

;; ENV
(defn get-env []
  "Returns current env vars as a Clojure map."
  (-> (.-env js/process)
      js->clj))

;; SNS utils
(defn get-sns-topic-from-env []
  (let [sns-topic (-> (get-env)
                      (aget "SNS_TOPIC"))]
    sns-topic))

(defn get-sns-client []
  (aws/SNS.))

(defn event->sns-event [event]
  (let [sms-msg-generator (fn [e] {:default e
                                   :sms e})]
    (-> event
        :body
        sms-msg-generator
        clj->json)))

(defn get-sns-params [topic subject msg]
  (-> {:Message msg
       :MessageStructure "json"
       :TopicArn topic
       :Subject subject}
      clj->js))

(defn get-sns-promise [sns-client params]
  "Send message to an SNS topic
   Returns a channel"
  (let [promise (.promise (.publish sns-client
                                    params))]
    promise))

;; any fatal errors will be caught by Lambda invocation
;; and wrapped by API Gateway, so we only return 200
;; on contact we DLQ on errors via AWS
(defgateway index [event ctx]
  (let [body {:body (body->layout-as-json index-data)}]
    (wrap-layout body 200)))

(defgateway about [event ctx]
  (let [body {:body (body->layout-as-json about-data)}]
    (wrap-layout body 200)))

(defgateway blog [event ctx]
  (let [body {:body (body->layout-as-json blog-data)}]
    (wrap-layout body 200)))

(defgateway contact [event ctx]
  (let [body {:body (body->layout-as-json contact-data)}]
    (wrap-layout body 200)))

(defgateway contact-form [event ctx]
  (let [topic (get-sns-topic-from-env)
        sns-client (get-sns-client)
        sns-message (event->sns-event event)
        params (get-sns-params topic "Contact Form" sns-message)
        sns-promise (get-sns-promise sns-client params)]
    (-> sns-promise
        (p/chain js->clj
                 #(wrap-layout {:body {:msg %}}
                               200)))))

