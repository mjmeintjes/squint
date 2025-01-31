(ns squint.internal.cli
  (:require
   ["fs" :as fs]
   ["path" :as path]
   [babashka.cli :as cli]
   [shadow.esm :as esm]
   [squint.compiler :as cc]
   [squint.compiler.node :as compiler]
   [squint.repl.node :as repl]))

(defn compile-files
  [opts files]
  (if (:help opts)
    (do (println "Usage: squint compile <files> <opts>")
        (println)
        (println "Options:

--elide-imports: do not include imports
--elide-exports: do not include exports"))
    (reduce (fn [prev f]
              (-> (js/Promise.resolve prev)
                  (.then
                   #(do
                      (println "[squint] Compiling CLJS file:" f)
                      (compiler/compile-file {:in-file f
                                              :elide-exports (:elide-exports opts)
                                              :elide-imports (:elide-imports opts)})))
                  (.then (fn [{:keys [out-file]}]
                           (println "[squint] Wrote JS file:" out-file)
                           out-file))))
            nil
            files)))

(defn print-help []
  (println "Squint v0.0.0

Usage: squint <opts>


Options:

-e        <expr>          Compile and run expression.
run       <file.cljs>     Compile and run a file
compile   <file.cljs> ... Compile file(s)
repl                      Start repl
help                      Print this help

Use squint <option> --help to show more info."))

(defn fallback [{:keys [rest-cmds opts]}]
  (if-let [e (:e opts)]
    (if (:help opts)
      (println "Usage: squint -e <expr> <opts>

Options:

--no-run: do not run compiled expression
--show:   print compiled expression")
      (let [res (cc/compile-string e)
            dir (fs/mkdtempSync ".tmp")
            f (str dir "/squint.mjs")]
        (fs/writeFileSync f res "utf-8")
        (when (:show opts)
          (println res))
        (when-not (:no-run opts)
          (let [path (if (path/isAbsolute f) f
                         (str (js/process.cwd) "/" f))]
            (-> (esm/dynamic-import path)
                (.finally (fn [_]
                            (fs/rmSync dir #js {:force true :recursive true}))))))))
    (if (or (:help opts)
            (= "help" (first rest-cmds))
            (empty? rest-cmds))
      (print-help)
      (compile-files opts rest-cmds))))

(defn run [{:keys [opts]}]
  (let [{:keys [file help]} opts]
    (if help
      nil
      (do (println "[squint] Running" file)
          (.then (compiler/compile-file {:in-file file})
                 (fn [{:keys [out-file]}]
                   (let [path (if (path/isAbsolute out-file) out-file
                                  (str (js/process.cwd) "/" out-file))]
                     (esm/dynamic-import path))))))))

#_(defn compile-form [{:keys [opts]}]
    (let [e (:e opts)]
      (println (t/compile! e))))

(def table
  [{:cmds ["run"]        :fn run :cmds-opts [:file]}
   {:cmds ["compile"]
    :coerce {:elide-exports :boolean
             :elide-imports :boolean}
    :fn (fn [{:keys [rest-cmds opts]}]
          (compile-files opts rest-cmds))}
   {:cmds ["repl"]       :fn repl/repl}
   {:cmds []             :fn fallback}])

(defn init []
  (cli/dispatch table
                (.slice js/process.argv 2)
                {:aliases {:h :help}}))
