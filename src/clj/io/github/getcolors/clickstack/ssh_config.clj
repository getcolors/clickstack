(ns io.github.getcolors.clickstack.ssh-config
  "The deployment's `~/.ssh/config` block, per the workspace SSH Config Standard.

  The block itself is written by the `ansible-local` stage, because that is the
  one place the address is known and because `blockinfile` already handles the
  idempotent replace. What lives here is everything that must happen before the
  stage renders: the alias, the identity file, and the refusal to adopt a
  stanza this package did not write.

  Unlike the keypair, this play is the package's own copy rather than ONCE's
  (standard §7). The file is shared with every other host the operator reaches,
  so an unrelated change upstream must not be able to rewrite it at pin-bump
  time."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def package "clickstack")

(defn host-alias
  "The profile, unchanged. Standard §2: the profile already keys remote state,
  which is what makes it unique enough to name a host by."
  [opts]
  (or (:profile opts) "clickstack"))

(defn identity-file
  "`~/.ssh/<profile>`, written with a literal tilde rather than an expanded
  home directory. OpenSSH expands it, and leaving it unexpanded is what keeps
  the rendered block identical on every workstation."
  [opts]
  (str "~/.ssh/" (host-alias opts)))

(defn config-path []
  (io/file (System/getProperty "user.home") ".ssh" "config"))

(defn begin-marker [alias] (str "# BEGIN " package " " alias " ANSIBLE MANAGED BLOCK"))
(defn end-marker [alias] (str "# END " package " " alias " ANSIBLE MANAGED BLOCK"))

(defn host-patterns
  "The patterns a `Host` line declares, or nil when the line is not one."
  [line]
  (when-let [[_ rest] (re-matches #"(?i)\s*Host\s+(.*?)\s*" line)]
    (remove str/blank? (str/split rest #"\s+"))))

(defn foreign-stanza-line
  "The 1-based line number of a `Host <alias>` stanza that this package did not
  write, or nil. Lines between our own markers are ours and are skipped."
  [lines alias]
  (loop [[line & more] lines n 1 inside? false]
    (cond
      (nil? line) nil
      (= (str/trim line) (begin-marker alias)) (recur more (inc n) true)
      (= (str/trim line) (end-marker alias)) (recur more (inc n) false)
      (and (not inside?) (some #{alias} (host-patterns line))) n
      :else (recur more (inc n) inside?))))

(defn adopt-error
  "The standard's never-adopt rule (§5). A hand-written `Host <profile>` stanza
  may be the operator's only record of how to reach something, so it stops the
  run rather than being overwritten."
  [opts]
  (let [f (config-path)]
    (when (.isFile f)
      (when-let [n (foreign-stanza-line (str/split-lines (slurp f)) (host-alias opts))]
        (str "refusing to manage " (.getPath f) ": it already declares "
             "`Host " (host-alias opts) "` at line " n
             " outside this package's managed block. Remove or rename that "
             "stanza if it is stale, or change `profile` if it belongs to "
             "something else; this package will not overwrite it.")))))

(defn preflight!
  "Run the never-adopt check. Real create only: build and dry-run must not read
  `~/.ssh/config` at all (§6)."
  [opts]
  (if-let [err (adopt-error opts)]
    (assoc opts :green/exit 1 :green/err err)
    opts))
