package com.intellij.roots;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkModificator;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.IdeaTestUtil;
import com.intellij.testFramework.ModuleTestCase;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.util.PathsList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * @author nik
 */
public abstract class ModuleRootManagerTestCase extends ModuleTestCase {
  protected static void assertRoots(PathsList pathsList, VirtualFile... files) {
    assertOrderedEquals(pathsList.getRootDirs(), files);
  }

  @Override
  protected Sdk getTestProjectJdk() {
    return getMockJdk17WithRtJarOnly();
  }

  @NotNull
  protected static Sdk getMockJdk17WithRtJarOnly() {
    return retainRtJarOnlyAndSetVersion(IdeaTestUtil.getMockJdk17());
  }

  protected Sdk getMockJdk18WithRtJarOnly() {
    return retainRtJarOnlyAndSetVersion(IdeaTestUtil.getMockJdk18());
  }

  @NotNull
  private static Sdk retainRtJarOnlyAndSetVersion(Sdk jdk) {
    final SdkModificator modificator = jdk.getSdkModificator();
    VirtualFile rtJar = null;
    for (VirtualFile root : modificator.getRoots(OrderRootType.CLASSES)) {
      if (root.getName().equals("rt.jar")) {
        rtJar = root;
        break;
      }
    }
    assertNotNull("rt.jar not found in jdk: " + jdk, rtJar);
    modificator.setVersionString(IdeaTestUtil.getMockJdkVersion(jdk.getHomePath()));
    modificator.removeAllRoots();
    modificator.addRoot(rtJar, OrderRootType.CLASSES);
    modificator.commitChanges();
    return jdk;
  }

  protected VirtualFile getRtJarJdk17() {
    return getMockJdk17WithRtJarOnly().getRootProvider().getFiles(OrderRootType.CLASSES)[0];
  }

  protected VirtualFile getRtJarJdk18() {
    return getMockJdk18WithRtJarOnly().getRootProvider().getFiles(OrderRootType.CLASSES)[0];
  }

  protected VirtualFile getJDomJar() {
    return getJarFromLibDir("jdom.jar");
  }

  protected VirtualFile getJDomSources() {
    return getJarFromLibDir("src/jdom.zip");
  }


  protected VirtualFile getJarFromLibDir(final String name) {
    final VirtualFile file = getVirtualFile(PathManager.findFileInLibDirectory(name));
    assertNotNull(name + " not found", file);
    final VirtualFile jarFile = JarFileSystem.getInstance().getJarRootForLocalFile(file);
    assertNotNull(name + " is not jar", jarFile);
    return jarFile;
  }

  protected VirtualFile addSourceRoot(Module module, boolean testSource) throws IOException {
    final VirtualFile root = getVirtualFile(createTempDir(module.getName() + (testSource ? "Test" : "Prod") + "Src"));
    PsiTestUtil.addSourceContentToRoots(module, root, testSource);
    return root;
  }

  protected VirtualFile setModuleOutput(final Module module, final boolean test) throws IOException {
    final VirtualFile output = getVirtualFile(createTempDir(module.getName() + (test ? "Test" : "Prod") + "Output"));
    PsiTestUtil.setCompilerOutputPath(module, output != null ? output.getUrl() : null, test);
    return output;
  }

  protected Library createLibrary(final String name, final @Nullable VirtualFile classesRoot, final @Nullable VirtualFile sourceRoot) {
    final Library library = LibraryTablesRegistrar.getInstance().getLibraryTable(myProject).createLibrary(name);
    final Library.ModifiableModel model = library.getModifiableModel();
    if (classesRoot != null) {
      model.addRoot(classesRoot, OrderRootType.CLASSES);
    }
    if (sourceRoot != null) {
      model.addRoot(sourceRoot, OrderRootType.SOURCES);
    }
    model.commit();
    return library;
  }

  protected Library createJDomLibrary() {
    return createLibrary("jdom", getJDomJar(), getJDomSources());
  }

  protected Library createAsmLibrary() {
    return createLibrary("asm", getAsmJar(), null);
  }

  protected VirtualFile getAsmJar() {
    return getJarFromLibDir("asm.jar");
  }
}
