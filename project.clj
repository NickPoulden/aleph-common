(defproject aleph "0.4.2-alpha7"
  :description "a framework for asynchronous communication"
  :repositories {"jboss" "http://repository.jboss.org/nexus/content/groups/public/"
                 "sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"}
  :license {:name "MIT License"}
  :dependencies [[org.clojure/tools.logging "0.3.1" :exclusions [org.clojure/clojure]]
                 [io.netty/netty-all "4.1.0.Final"]
                 [manifold "0.1.5"]
                 [byte-streams "0.2.2"]
                 [potemkin "0.4.3"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.8.0"]
                                  [criterium "0.4.4"]]}}
  :codox {:source-uri "https://github.com/ztellman/aleph/blob/master/{filepath}#L{line}"
          :metadata {:doc/format :markdown}
          :namespaces [aleph.tcp aleph.udp aleph.http aleph.flow]}
  :plugins [[lein-codox "0.9.4"]
            [lein-jammin "0.1.1"]
            [lein-marginalia "0.9.0"]
            [ztellman/lein-cljfmt "0.1.10"]]
  :cljfmt {:indents {#".*" [[:inner 0]]}}
  :test-selectors {:default #(not
                               (some #{:benchmark :stress}
                                 (cons (:tag %) (keys %))))
                   :benchmark :benchmark
                   :stress :stress
                   :all (constantly true)}
  :jvm-opts ^:replace ["-server"
                       "-XX:+UseConcMarkSweepGC"
                       #_"-Xmx256m"
                       "-Xmx2g"
                       "-XX:+HeapDumpOnOutOfMemoryError"
                       #_"-XX:+PrintCompilation"
                       #_"-XX:+UnlockDiagnosticVMOptions"
                       #_"-XX:+PrintInlining"]
  :global-vars {*warn-on-reflection* true})
