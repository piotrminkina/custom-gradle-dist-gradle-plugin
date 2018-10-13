package tech.harmonysoft.oss.gradle.dist

import org.gradle.testkit.runner.GradleRunner
import org.junit.Test

import java.nio.charset.StandardCharsets
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import static java.util.function.Function.identity
import static java.util.stream.Collectors.toMap
import static org.junit.Assert.*

class CustomGradleDistributionPluginTest {

    private static final String GRADLE_VERSION = '4.10'
    private static final String PROJECT_NAME = 'my-project'
    private static final String PROJECT_VERSION = '1.0'

    private static final BUILD_TEMPLATE = """
plugins {
    id 'tech.harmonysoft.gradle-dist-plugin'
}

gradleDist {
    gradleVersion = '$GRADLE_VERSION'
    customDistributionVersion = '$PROJECT_VERSION'
    customDistributionName = '$PROJECT_NAME'
}
"""

    @Test
    void 'when client project has a single distribution and no templates then it is correctly packaged'() {
        doTest('single-distribution-no-templates')
    }

    @Test
    void 'when client project has a single distribution and single template then it is correctly packaged'() {
        doTest('single-distribution-single-template')
    }

    private void doTest(String testName) {
        def testFiles = prepareInput(testName)
        GradleRunner.create()
                    .withProjectDir(testFiles.inputRootDir)
                    .withArguments('build')
                    .withPluginClasspath()
                    .withDebug(true)
                    .build()
        verify(testFiles.expectedRootDir, new File(testFiles.inputRootDir, 'build/gradle-dist'))
    }

    private TestFiles prepareInput(String testDir) {
        def testRoot = new File(getClass().getResource("/$testDir").file)
        def inputRootDir = copy(new File(testRoot, 'input'))
        createGradleFile(inputRootDir)
        createGradleDistributionZip(inputRootDir)
        return new TestFiles(inputRootDir, new File(testRoot, 'expected-init.d'))
    }

    private static File copy(File dir) {
        def result = Files.createTempDirectory("${dir.name}-tmp").toFile()
        def resourcesRoot = new File(result, 'src/main/resources')
        Files.createDirectories(resourcesRoot.toPath())
        def children = dir.listFiles()
        for (child in children) {
            copy(child, resourcesRoot)
        }
        return result
    }

    private static void copy(File toCopy, File destinationDir) {
        if (toCopy.file) {
            Files.copy(toCopy.toPath(), new File(destinationDir, toCopy.name).toPath())
        } else {
            def children = toCopy.listFiles()
            if (children != null) {
                def dir = new File(destinationDir, toCopy.name)
                Files.createDirectories(dir.toPath())
                for (child in children) {
                    copy(child, dir)
                }
            }
        }
    }

    private static void createGradleFile(File projectRootDir) {
        Files.write(new File(projectRootDir, 'build.gradle').toPath(),
                    BUILD_TEMPLATE.getBytes(StandardCharsets.UTF_8))
    }

    private static void createGradleDistributionZip(File projectRootDir) {
        def downloadDir = new File(projectRootDir, "build/download")
        Files.createDirectories(downloadDir.toPath())
        def zip = new File(downloadDir, "gradle-${GRADLE_VERSION}-bin.zip")
        new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zip))).withCloseable {
            def entry = new ZipEntry("gradle-$GRADLE_VERSION/")
            it.putNextEntry(entry)
            it.closeEntry()
        }
    }

    private static void verify(File expectedRootDir, File actualDistDir) {
        def filter = { it.directory } as FileFilter
        def expectedDistributions = expectedRootDir.listFiles(filter)
        if (expectedDistributions.length == 0) {
            assertEquals(actualDistDir.list().length, 1)
            verify(expectedRootDir, getZipFs(actualDistDir, null))
        }
    }

    private static FileSystem getZipFs(File parentDir, String distribution) {
        def zipName = "gradle-$GRADLE_VERSION-$PROJECT_NAME-$PROJECT_VERSION"
        if (distribution != null) {
            zipName += "-$distribution"
        }
        zipName += '.zip'
        def zip = new  File(parentDir, zipName)
        assertTrue(zip.file)
        return FileSystems.newFileSystem(URI.create("jar:${zip.toPath().toUri()}"), ['create': 'false'])
    }

    private static void verify(File expectedDir, FileSystem zipFs) {
        verify(expectedDir, zipFs.getPath("gradle-$GRADLE_VERSION/init.d"))
    }

    private static void verify(File expectedDir, Path actualDir) {
        def keyExtractor = { Path path ->
            def pathAsString = path.toString()
            def i = pathAsString.indexOf('init.d')
            return pathAsString.substring(i + 'init.d/'.length())
        }
        Map<String, Path> allExpected = Files.list(expectedDir.toPath()).collect(toMap(keyExtractor, identity()))
        Map<String, Path> allActual = Files.list(actualDir).collect(toMap(keyExtractor, identity()))

        if (allExpected.size() != allActual.size()) {
            def unexpected = new HashSet(allActual.keySet())
            unexpected.removeAll(allActual.keySet())
            if (!unexpected.empty) {
                fail("Unexpected entries are found in the custom distribution's 'init.d' directory: $unexpected")
            }

            def unmatched = new HashSet(allExpected.keySet())
            unmatched.removeAll(allActual.keySet())
            if (!unmatched.empty) {
                fail("Expected entries are not found in the custom distribution's 'init.d' directory: $unexpected")
            }
        }

        allExpected.each { pathKey, path ->
            if (!allActual.containsKey(pathKey)) {
                fail("Expected to find '$pathKey' in the custom distribution's 'init.d' directory but it's not there")
            }
            if (path.toFile().file) {
                def expectedText = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                def actualText = new String(Files.readAllBytes(allActual.get(pathKey)), StandardCharsets.UTF_8)
                assertEquals("Text mismatch in $path", expectedText, actualText)
            }
        }
    }

    private static class TestFiles {

        File inputRootDir
        File expectedRootDir

        TestFiles(File inputRootDir, File expectedRootDir) {
            this.inputRootDir = inputRootDir
            this.expectedRootDir = expectedRootDir
        }
    }
}