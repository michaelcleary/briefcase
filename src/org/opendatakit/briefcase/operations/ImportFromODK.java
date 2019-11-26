/*
 * Copyright (C) 2018 Nafundi
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.opendatakit.briefcase.operations;

import static java.util.stream.Collectors.toList;
import static org.opendatakit.briefcase.model.form.FormMetadataCommands.updateAsPulled;
import static org.opendatakit.briefcase.operations.Common.FORM_ID;
import static org.opendatakit.briefcase.operations.Common.STORAGE_DIR;
import static org.opendatakit.briefcase.reused.job.Job.run;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import org.bushe.swing.event.EventBus;
import org.opendatakit.briefcase.model.FormStatus;
import org.opendatakit.briefcase.model.TerminationFuture;
import org.opendatakit.briefcase.model.form.FileSystemFormMetadataAdapter;
import org.opendatakit.briefcase.model.form.FormKey;
import org.opendatakit.briefcase.model.form.FormMetadataPort;
import org.opendatakit.briefcase.pull.PullEvent;
import org.opendatakit.briefcase.reused.BriefcaseException;
import org.opendatakit.briefcase.reused.job.JobsRunner;
import org.opendatakit.briefcase.transfer.TransferForms;
import org.opendatakit.briefcase.util.FileSystemUtils;
import org.opendatakit.briefcase.util.FormCache;
import org.opendatakit.briefcase.util.TransferFromODK;
import org.opendatakit.common.cli.Operation;
import org.opendatakit.common.cli.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ImportFromODK {
  private static final Logger log = LoggerFactory.getLogger(ImportFromODK.class);
  private static final Param<Void> IMPORT = Param.flag("pc", "pull_collect", "Pull from Collect");
  private static final Param<Path> ODK_DIR = Param.arg("od", "odk_directory", "ODK directory", Common::absolutePath);

  public static final Operation IMPORT_FROM_ODK = Operation.of(
      IMPORT,
      args -> importODK(
          args.get(STORAGE_DIR),
          args.get(ODK_DIR),
          args.getOptional(FORM_ID)
      ),
      Arrays.asList(STORAGE_DIR, ODK_DIR),
      Arrays.asList(FORM_ID)
  );

  public static void importODK(Path storageDir, Path odkDir, Optional<String> formId) {
    CliEventsCompanion.attach(log);
    Path briefcaseDir = Common.getOrCreateBriefcaseDir(storageDir);
    FormCache formCache = FormCache.from(briefcaseDir);
    formCache.update();
    FormMetadataPort formMetadataPort = FileSystemFormMetadataAdapter.at(briefcaseDir);

    TransferForms forms = TransferForms.from(FileSystemUtils.getODKFormList(odkDir.toFile()).stream()
        .map(FormStatus::new)
        .filter(form -> formId.map(id -> form.getFormDefinition().getFormId().equals(id)).orElse(true))
        .collect(toList()));
    forms.selectAll();

    if (formId.isPresent() && forms.isEmpty())
      throw new BriefcaseException("Form " + formId.get() + " not found");

    JobsRunner.launchAsync(forms.map(form -> {
      TransferFromODK action = new TransferFromODK(briefcaseDir, odkDir.toFile(), new TerminationFuture(), TransferForms.of(form));
      return run(jobStatus -> {
        try {
          boolean success = action.doAction();
          if (success) {
            EventBus.publish(PullEvent.Success.of(form));
            formMetadataPort.execute(updateAsPulled(FormKey.from(form), briefcaseDir, form.getFormDir(briefcaseDir)));
          } // TODO Originally there was no explicit side effect on non successful individual pulls. We might want to give some feedback
        } catch (Exception e) {
          // This will lift any checked exception thrown by the underlying code
          // into a BriefcaseException that is managed by the error management
          // flow driven by the Launcher class
          throw new BriefcaseException("Failed to pull form (legacy)", e);
        }
      });
    })).onComplete(() -> EventBus.publish(new PullEvent.PullComplete())).waitForCompletion();
  }
}
