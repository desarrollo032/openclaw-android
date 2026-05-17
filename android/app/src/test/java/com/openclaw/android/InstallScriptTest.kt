package com.openclaw.android

import com.openclaw.android.proot.InstallScript
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

class InstallScriptTest {

    @Test
    fun `installer skips pnpm and installs openclaw with npm`() {
        val script = InstallScript.generate()

        script.shouldContain("pnpm se omite")
        script.shouldContain("npm install -g ${'$'}ocPackage")
        script.shouldNotContain("pnpm add -g")
        script.shouldNotContain("apk add --no-progress pnpm")
        script.shouldNotContain("corepack prepare pnpm")
        script.shouldNotContain("npm install -g pnpm")
        script.shouldNotContain("No se pudo instalar OpenClaw (pnpm fallo)")
        script.shouldNotContain("No se pudo instalar OpenClaw (pnpm falló)")
    }

    @Test
    fun `installer uses manual node inside alpine proot`() {
        val script = InstallScript.generate()

        script.shouldContain("nodejs.org/dist")
        script.shouldContain("node-v${'$'}{NODE_VERSION}-linux-arm64")
        script.shouldContain("/usr/local/node")
        script.shouldContain("proot-cwd-fix.cjs")
        script.shouldContain("NODE_OPTIONS")
    }

    @Test
    fun `installer prepares alpine directories dns repositories and apk cache`() {
        val script = InstallScript.generate()

        script.shouldContain("mkdir -p /root/tmp /root/.openclaw /usr/local")
        script.shouldContain("nameserver 1.1.1.1")
        script.shouldContain("/etc/apk/repositories")
        script.shouldContain("rm -rf /var/cache/apk")
        script.shouldContain("apk update")
    }

    @Test
    fun `installer leaves onboarding for the interactive terminal`() {
        val script = InstallScript.generate()

        script.shouldContain("openclaw onboard se abrira en el terminal")
        script.shouldNotContain("echo \"\" | openclaw onboard")
        script.shouldNotContain("echo '' | openclaw onboard")
    }
}
