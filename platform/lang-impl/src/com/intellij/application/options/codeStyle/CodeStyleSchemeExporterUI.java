/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.application.options.codeStyle;

import com.intellij.openapi.application.ApplicationBundle;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileChooser.FileSaverDialog;
import com.intellij.openapi.options.SchemeExporter;
import com.intellij.openapi.options.SchemeExporterEP;
import com.intellij.openapi.options.SchemeExporterException;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.psi.codeStyle.CodeStyleScheme;
import com.intellij.util.ArrayUtil;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;


/**
 * @author Rustam Vishnyakov
 */
class CodeStyleSchemeExporterUI {
  @NotNull private final Component myParentComponent;
  @NotNull private final CodeStyleScheme myScheme;
  @NotNull private final StatusCallback myStatusCallback;

  CodeStyleSchemeExporterUI(@NotNull Component parentComponent, @NotNull CodeStyleScheme scheme, @NotNull StatusCallback statusCallback) {
    myParentComponent = parentComponent;
    myScheme = scheme;
    myStatusCallback = statusCallback;
  }

  void export() {
    ListPopup popup = JBPopupFactory.getInstance().createListPopup(
      new BaseListPopupStep<String>(ApplicationBundle.message("code.style.scheme.exporter.ui.export.as.title"), enumExporters()) {
        @Override
        public PopupStep onChosen(final String selectedValue, boolean finalChoice) {
          return doFinalStep(new Runnable() {
            @Override
            public void run() {
              exportSchemeUsing(selectedValue);
            }
          });
        }
      });
    popup.showInCenterOf(myParentComponent);
  }

  private static String[] enumExporters() {
    List<String> names = new ArrayList<String>();
    Collection<SchemeExporterEP<CodeStyleScheme>> extensions = SchemeExporterEP.getExtensions(CodeStyleScheme.class);
    for (SchemeExporterEP<CodeStyleScheme> extension : extensions) {
      names.add(extension.name);
    }
    return ArrayUtil.toStringArray(names);
  }

  private void exportSchemeUsing(@NotNull String exporterName) {
    SchemeExporter<CodeStyleScheme> exporter = SchemeExporterEP.getExporter(exporterName, CodeStyleScheme.class);
    if (exporter != null) {
      String ext = exporter.getExtension();
      FileSaverDialog saver =
        FileChooserFactory.getInstance()
          .createSaveFileDialog(new FileSaverDescriptor(
            ApplicationBundle.message("code.style.scheme.exporter.ui.file.chooser.title"),
            ApplicationBundle.message("code.style.scheme.exporter.ui.file.chooser.message"),
            ext), myParentComponent);
      VirtualFileWrapper target = saver.save(null, getFileNameSuggestion() + "." + ext);
      if (target != null) {
        VirtualFile targetFile = target.getVirtualFile(true);
        if (targetFile != null) {
          try {
            exporter.exportScheme(myScheme, targetFile);
            myStatusCallback
              .showMessage(ApplicationBundle.message("code.style.scheme.exporter.ui.code.style.exported.message", myScheme.getName(),
                                                     targetFile.getPresentableUrl()), MessageType.INFO);
          }
          catch (SchemeExporterException e) {
            myStatusCallback.showMessage(e.getMessage(), MessageType.ERROR);
          }
        }
        else {
          myStatusCallback.showMessage(ApplicationBundle.message("code.style.scheme.exporter.ui.cannot.write.message"), MessageType.ERROR);
        }
      }
    }
  }

  private String getFileNameSuggestion() {
    return myScheme.getName();
  }

  interface StatusCallback {
    void showMessage(@NotNull String message, @NotNull MessageType messageType);
  }
}
