import org.junit.Before
import org.junit.Test
import static org.junit.Assert.*

/**
 * Tests for the broker CA injection in pod_label.withCiLabels.
 *
 * Usage:
 *   groovy libraries/tisys/tests/TestPodLabel.groovy
 *
 * Every job passes its pod template through this helper, so it is the one place
 * that can hand the Jenkins agents the broker CA without editing ~300 pod.yaml
 * files. The injection is gated on an https broker URL: on the GCP cluster the
 * agents still call the plaintext listener and must not gain a mount they do
 * not need.
 */
class TestPodLabel {

    private static final String POD = '''
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: golang
      image: example/golang:latest
      volumeMounts:
        - name: tmp
          mountPath: /tmp
  volumes:
    - name: tmp
      emptyDir: {}
'''

    private static def loadPodLabel(Map env, Map captured) {
        def binding = new Binding()
        binding.setVariable('env', env)
        binding.setVariable('readTrusted', { String path -> POD })
        binding.setVariable('echo', { String message -> })
        binding.setVariable('readYaml', { Map args -> new groovy.yaml.YamlSlurper().parseText(args.text) })
        binding.setVariable('writeYaml', { Map args ->
            captured.podSpec = args.data
            'rendered'
        })
        new GroovyShell(binding).parse(
            new File('libraries/tisys/vars/pod_label.groovy'))
    }

    @Test
    void shouldMountTheBrokerCaForAnHttpsBroker() {
        def captured = [:]
        def script = loadPodLabel([GIT_BROKER_URL: 'https://broker.example'], captured)

        assertEquals('rendered', script.withCiLabels('jenkins/jobs/example/pod.yaml', [:]))

        def spec = captured.podSpec.spec
        def volume = spec.volumes.find { it.name == 'github-token-broker-ca' }
        assertNotNull('CA volume injected', volume)
        assertEquals('configMap', (volume.configMap != null) ? 'configMap' : 'other')
        assertEquals('github-token-broker-ca', volume.configMap.name)
        assertEquals('must not block pods in namespaces without the CA', true, volume.configMap.optional)

        spec.containers.each { container ->
            def mount = container.volumeMounts?.find { it.name == 'github-token-broker-ca' }
            assertNotNull("CA mounted into ${container.name}", mount)
            assertEquals('/etc/github-token-broker/ca', mount.mountPath)
            assertEquals(true, mount.readOnly)
            assertNull('no subPath, so renewals propagate', mount.subPath)
        }
        assertTrue('existing mounts preserved',
            spec.containers[0].volumeMounts.any { it.name == 'tmp' })
    }

    @Test
    void shouldLeavePlaintextPodsAlone() {
        def captured = [:]
        def script = loadPodLabel([GIT_BROKER_URL: 'http://broker.example'], captured)

        // Nothing to inject and no annotations: the original text is returned
        // untouched, which is the cheapest proof that no CA reached the pod.
        def rendered = script.withCiLabels('jenkins/jobs/example/pod.yaml', [:])

        assertFalse('no CA volume for a plaintext broker',
            rendered.toString().contains('github-token-broker-ca'))
        assertNull('pod spec not rewritten', captured.podSpec)
    }

    @Test
    void shouldNotDuplicateAnExistingMount() {
        def captured = [:]
        def script = loadPodLabel([GIT_BROKER_URL: 'https://broker.example'], captured)

        def podWithCa = POD.replace('''  volumes:
    - name: tmp
      emptyDir: {}''', '''  volumes:
    - name: tmp
      emptyDir: {}
    - name: github-token-broker-ca
      configMap:
        name: github-token-broker-ca
        optional: true''')
        script.metaClass = null
        def binding = new Binding()
        binding.setVariable('env', [GIT_BROKER_URL: 'https://broker.example'])
        binding.setVariable('readTrusted', { String path -> podWithCa })
        binding.setVariable('echo', { String message -> })
        binding.setVariable('readYaml', { Map args -> new groovy.yaml.YamlSlurper().parseText(args.text) })
        binding.setVariable('writeYaml', { Map args ->
            captured.podSpec = args.data
            'rendered'
        })
        def script2 = new GroovyShell(binding).parse(
            new File('libraries/tisys/vars/pod_label.groovy'))

        script2.withCiLabels('jenkins/jobs/example/pod.yaml', [:])

        def volumes = captured.podSpec.spec.volumes.findAll { it.name == 'github-token-broker-ca' }
        assertEquals('volume stays single', 1, volumes.size())
    }
}
