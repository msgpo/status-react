(ns status-im.ui.screens.chat.message.message
  (:require-macros [status-im.utils.views :refer [defview letsubs]])
  (:require [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [status-im.ui.components.react :as react]
            [status-im.ui.components.animation :as animation]
            [status-im.ui.components.list-selection :as list-selection]
            [status-im.ui.components.icons.vector-icons :as vector-icons]
            [status-im.ui.components.action-sheet :as action-sheet]
            [status-im.chat.commands.core :as commands]
            [status-im.chat.commands.receiving :as commands-receiving]
            [status-im.ui.screens.chat.styles.message.message :as style]
            [status-im.ui.screens.chat.photos :as photos]
            [status-im.constants :as constants]
            [status-im.ui.components.chat-icon.screen :as chat-icon.screen]
            [status-im.utils.core :as utils]
            [status-im.ui.screens.chat.utils :as chat-utils]
            [status-im.utils.identicon :as identicon]
            [status-im.utils.gfycat.core :as gfycat]
            [status-im.utils.platform :as platform]
            [status-im.i18n :as i18n]
            [status-im.ui.components.colors :as colors]
            [clojure.string :as string]))

(defview message-content-command
  [command-message]
  (letsubs [id->command [:get-id->command]]
    (if-let [command (commands-receiving/lookup-command-by-ref command-message id->command)]
      (commands/generate-preview command command-message)
      [react/text (str "Unhandled command: " (-> command-message :content :command-path first))])))

(defview message-timestamp [t justify-timestamp? outgoing command? content]
  (when-not command?
    [react/text {:style (style/message-timestamp-text justify-timestamp? outgoing (:rtl? content))} t]))

(defn message-view
  [{:keys [timestamp-str outgoing content] :as message} message-content {:keys [justify-timestamp?]}]
  [react/view (style/message-view message)
   message-content
   [message-timestamp timestamp-str justify-timestamp? outgoing (or (get content :command-path)
                                                                    (get content :command-ref))
    content]])

; We can't use CSS as nested Text element don't accept margins nor padding
; so we pad the invisible placeholder with some spaces to avoid having too
; close to the text.
(defn timestamp-with-padding [t]
  (str "   " t))

(defn- expand-button [collapsed? on-press]
  [react/text {:style    style/message-expand-button
               :on-press on-press}
   (i18n/label (if @collapsed? :show-more :show-less))])

(defview quoted-message [{:keys [from text]} outgoing current-public-key]
  (letsubs [username [:get-contact-name-by-identity from]]
    [react/view {:style (style/quoted-message-container outgoing)}
     [react/view {:style style/quoted-message-author-container}
      [vector-icons/icon :icons/reply {:color (if outgoing colors/wild-blue-yonder colors/gray)}]
      [react/text {:style (style/quoted-message-author outgoing)}
       (chat-utils/format-reply-author from username current-public-key)]]
     [react/text {:style           (style/quoted-message-text outgoing)
                  :number-of-lines 5}
      text]]))

(def ^:private kind->props-fn {:bold   (constantly {:style {:font-weight :bold}})
                               :italic (constantly {:style {:font-style  :italic}})
                               :link   (fn [text {:keys [outgoing]}]
                                         {:style    {:color                (if outgoing colors/white colors/blue)
                                                     :text-decoration-line :underline}
                                          :on-press #(re-frame/dispatch [:browser.ui/message-link-pressed text])})
                               :tag    (fn [text {:keys [outgoing]}]
                                         {:style    {:color                (if outgoing colors/white colors/blue)
                                                     :text-decoration-line :underline}
                                          :on-press #(re-frame/dispatch [:chat.ui/start-public-chat (subs text 1)])})})

(defn- render-chunks [render-recipe message]
  (map-indexed (fn [idx [text kind]]
                 [react/text
                  (merge {:key idx}
                         (apply merge-with merge
                                (keep (comp #(when % (% text message)) kind->props-fn) kind)))
                  text])
               render-recipe))

(defn text-message
  [{:keys [content timestamp-str group-chat outgoing current-public-key] :as message}]
  [message-view message
   (let [ref (reagent/atom nil)
         collapsible? (and (:should-collapse? content) group-chat)
         collapsed? (reagent/atom collapsible?)
         on-press (when collapsible?
                    #(do
                       (.setNativeProps @ref
                                        (clj->js {:numberOfLines
                                                  (when-not @collapsed?
                                                    constants/lines-collapse-threshold)}))
                       (reset! collapsed? (not @collapsed?))))]
     [react/view
      (when (:response-to content)
        [quoted-message (:response-to content) outgoing current-public-key])
      [react/text {:style           (style/text-message collapsible? outgoing)
                   :number-of-lines (when collapsible? constants/lines-collapse-threshold)
                   :ref             (partial reset! ref)}
       (if-let [render-recipe (:render-recipe content)]
         (render-chunks render-recipe message)
         (:text content))
       [react/text {:style (style/message-timestamp-placeholder-text outgoing)} (timestamp-with-padding timestamp-str)]]
      (when collapsible?
        [expand-button collapsed? on-press])])
   {:justify-timestamp? true}])

(defn emoji-message
  [{:keys [content] :as message}]
  [message-view message
   [react/text {:style (style/emoji-message message)} (:text content)]])

(defmulti message-content (fn [_ message _] (message :content-type)))

(defmethod message-content constants/content-type-text
  [wrapper message]
  [wrapper message [text-message message]])

(defmethod message-content constants/content-type-command
  [wrapper message]
  [wrapper message
   [message-view message [message-content-command message]]])

(defmethod message-content constants/content-type-emoji
  [wrapper message]
  [wrapper message [emoji-message message]])

(defmethod message-content :default
  [wrapper {:keys [content-type] :as message}]
  [wrapper message
   [message-view message
    [react/text {} (str "Unhandled content-type " content-type)]]])

(defn- text-status [status]
  [react/view style/delivery-view
   [react/text {:style style/delivery-text
                :font  :default}
    (i18n/message-status-label status)]])

(defview group-message-delivery-status [{:keys [message-id current-public-key user-statuses] :as msg}]
  (letsubs [{participants :contacts} [:get-current-chat]
            contacts                 [:get-contacts]]
    (let [outgoing-status         (or (get-in user-statuses [current-public-key :status]) :sending)
          delivery-statuses       (dissoc user-statuses current-public-key)
          delivery-statuses-count (count delivery-statuses)
          seen-by-everyone        (and (= delivery-statuses-count (count participants))
                                       (every? (comp (partial = :seen) :status second) delivery-statuses)
                                       :seen-by-everyone)]
      (if (or seen-by-everyone (zero? delivery-statuses-count))
        [text-status (or seen-by-everyone outgoing-status)]
        [react/touchable-highlight
         {:on-press #(re-frame/dispatch [:chat.ui/show-message-details {:message-status outgoing-status
                                                                        :user-statuses  delivery-statuses
                                                                        :participants   participants}])}
         [react/view style/delivery-view
          (for [[whisper-identity] (take 3 delivery-statuses)]
            ^{:key whisper-identity}
            [react/image {:source {:uri (or (get-in contacts [whisper-identity :photo-path])
                                            (identicon/identicon whisper-identity))}
                          :style  {:width         16
                                   :height        16
                                   :border-radius 8}}])
          (if (> delivery-statuses-count 3)
            [react/text {:style style/delivery-text
                         :font  :default}
             (str "+ " (- delivery-statuses-count 3))])]]))))

(defn message-activity-indicator []
  [react/view style/message-activity-indicator
   [react/activity-indicator {:animating true}]])

(defn message-not-sent-text [chat-id message-id]
  [react/touchable-highlight {:on-press (fn [] (if platform/ios?
                                                 (action-sheet/show {:title   (i18n/label :message-not-sent)
                                                                     :options [{:label  (i18n/label :resend-message)
                                                                                :action #(re-frame/dispatch [:chat.ui/resend-message chat-id message-id])}
                                                                               {:label        (i18n/label :delete-message)
                                                                                :destructive? true
                                                                                :action       #(re-frame/dispatch [:chat.ui/delete-message chat-id message-id])}]})
                                                 (re-frame/dispatch
                                                  [:chat.ui/show-message-options {:chat-id    chat-id
                                                                                  :message-id message-id}])))}
   [react/view style/not-sent-view
    [react/text {:style style/not-sent-text}
     (i18n/message-status-label (if platform/desktop?
                                  :not-sent-without-tap
                                  :not-sent))]
    (when-not platform/desktop?
      [react/view style/not-sent-icon
       [vector-icons/icon :icons/warning {:color colors/red}]])]])

(defview command-status [{{:keys [network]} :params}]
  (letsubs [current-network [:network-name]]
    (when (and network (not= current-network network))
      [react/view style/not-sent-view
       [react/text {:style style/not-sent-text}
        (i18n/label :network-mismatch)]
       [react/view style/not-sent-icon
        [vector-icons/icon :icons/warning {:color colors/red}]]])))

(defn message-delivery-status
  [{:keys [chat-id message-id current-public-key user-statuses content last-outgoing? outgoing message-type] :as message}]
  (let [outgoing-status (or (get-in user-statuses [current-public-key :status]) :not-sent)
        delivery-status (get-in user-statuses [chat-id :status])
        status          (or delivery-status outgoing-status)]
    (case status
      :sending  [message-activity-indicator]
      :not-sent [message-not-sent-text chat-id message-id]
      (if (and (not outgoing)
               (:command content))
        [command-status content]
        (when last-outgoing?
          (if (= message-type :group-user-message)
            [group-message-delivery-status message]
            (if outgoing
              [text-status status])))))))

(defview message-author-name [from message-username]
  (letsubs [username [:get-contact-name-by-identity from]]
    [react/text {:style style/message-author-name}
     (chat-utils/format-author from (or username message-username))]))

(defn message-body
  [{:keys [last-in-group?
           display-photo?
           display-username?
           from
           outgoing
           modal?
           username] :as message} content]
  [react/view (style/group-message-wrapper message)
   [react/view (style/message-body message)
    (when display-photo?
      [react/view style/message-author
       (when last-in-group?
         [react/touchable-highlight {:on-press #(when-not modal? (re-frame/dispatch [:chat.ui/show-profile from]))}
          [react/view
           [photos/member-photo from]]])])
    [react/view (style/group-message-view outgoing)
     (when display-username?
       [message-author-name from username])
     [react/view {:style (style/timestamp-content-wrapper message)}
      content]]]
   [react/view (style/delivery-status outgoing)
    [message-delivery-status message]]])

(defn chat-message [{:keys [message-id outgoing group-chat modal? current-public-key content-type content] :as message}]
  [react/view
   [react/touchable-highlight {:on-press      (fn [_]
                                                (re-frame/dispatch [:chat.ui/set-chat-ui-props {:messages-focused? true}])
                                                (react/dismiss-keyboard!))
                               :on-long-press #(when (= content-type constants/content-type-text)
                                                 (list-selection/chat-message message-id (:text content) (i18n/label :t/message)))}
    [react/view {:accessibility-label :chat-item}
     (let [incoming-group (and group-chat (not outgoing))]
       [message-content message-body (merge message
                                            {:current-public-key current-public-key
                                             :group-chat         group-chat
                                             :modal?             modal?
                                             :incoming-group     incoming-group})])]]])
