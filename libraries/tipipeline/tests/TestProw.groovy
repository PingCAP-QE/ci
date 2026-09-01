/**
 * Keep Jenkins shared library entrypoints loadable by the Groovy CPS runtime.
 *
 * A syntax error in a vars/*.groovy file otherwise appears only when a real
 * Jenkins job loads the shared library.
 */
new GroovyShell().parse(new File("libraries/tipipeline/vars/prow.groovy"))
println "prow.groovy syntax OK"
