(ns pepa.components.sidebar
  (:require [om.core :as om :include-macros true]
            [sablono.core :refer-macros [html]]
            [clojure.string :as s]
            [cljs.core.async :as async]

            [pepa.navigation :as nav]
            [pepa.search :refer [search-query]]
            [pepa.components.logo :as logo]
            [pepa.api.upload :as upload]

            [pepa.search.parser :as parser])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn ^:private search-field [_ owner opts]
  (reify
    om/IInitState
    (init-state [_]
      {:query nil})
    om/IRenderState
    (render-state [_ {:keys [query]}]
      (html [:form.search {:on-submit (fn [e]
                                        (-> (if (seq query)
                                              (nav/full-search query)
                                              (nav/dashboard-route))
                                            (nav/navigate!))
                                        (.preventDefault e))
                           ;; TODO: Search on tag-drop & document-drop (by id?)
                           ;; :on-drop #(js/console.log %)
                           }
             [:input {:value (or query "")
                      :placeholder "Search"
                      :on-change (fn [e]
                                   (om/set-state! owner :query
                                                  e.currentTarget.value)
                                   true)}]]))))

(defmulti navigation-element (fn [_ _ [name id _ route]] id))

(defmethod navigation-element :default [state _ [title id _ href]]
  (om/component
   (html
    [:a {:href href}
     [:div title]])))

(defn ^:private inbox-drop! [state owner e]
  ;; NOTE: We need to handle all event-object interop BEFORE entering
  ;; a go-block
  (let [files (upload/data-transfer-files event.dataTransfer)]
    (go
      (try
        (om/set-state! owner :working? true)
        (doseq [f files]
          (let [blob (<! (upload/file->u8arr f))]
            (<! (upload/upload-to-inbox! blob))))

        ;; TODO(mu): Remove this when the UI is working fine
        (<! (async/timeout 1000))
        (finally
          (doto owner
            (om/set-state! :working? false)
            (om/set-state! :drop? false))))))
  (.preventDefault e))

(defmethod navigation-element :inbox [state owner [title id _ href]]
  (reify
    om/IRenderState
    (render-state [_ {:keys [drop? working?]}]
      (html
       [:a {:href href}
        [:div {:class [(when working? "working")
                       (when drop? "drop-target")]
               :on-drag-over upload/accept-file-drag
               :on-drag-enter #(om/set-state! owner :drop? true)
               :on-drag-leave #(om/set-state! owner :drop? false)
               :on-drop (partial inbox-drop! state owner)} 
         title
         (when-let [pages (-> state :workflows :inbox :documents :inbox :pages seq)]
           [:span.count (count pages)])]]))))

(defn ^:private route-matches? [route workflows]
  (let [route (if (seqable? route)
                (first route)
                route)]
    (or (= route workflows)
        (and (set? workflows) (contains? workflows route))
        (and (fn? workflows) (workflows route)))))

(def navigation-elements
  [["Inbox"     :inbox     #{:inbox}     (nav/workflow-route :inbox)]
   ["Documents" :dashboard #{:dashboard :search} (nav/dashboard-route)]])

(defn sidebar-component [state]
  (let [route (om/value (get-in state [:navigation :route]))]
    (reify
      om/IRenderState
      (render-state [_ {:keys [width]}]
        (html
         [:#sidebar {:style {:width (str width "px")}}
          (om/build logo/xeyes nil)

          (om/build search-field nil
                    {:state {:query (search-query state)}})
          
          [:ul
           (for [element navigation-elements]
             (let [[title ident routes href] element]
               [:li {:class [(name ident)
                             (when (route-matches? route routes) "active")]
                     :key (name ident)}
                ;; TODO: Use build-all
                (om/build navigation-element state {:opts element})]))]])))))
