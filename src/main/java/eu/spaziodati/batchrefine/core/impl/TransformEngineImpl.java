package eu.spaziodati.batchrefine.core.impl;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.ImporterTopLevel;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.ScriptableObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.Configurations;
import com.google.refine.ProjectManager;
import com.google.refine.ProjectMetadata;
import com.google.refine.RefineServlet;
import com.google.refine.browsing.Engine;
import com.google.refine.exporters.CsvExporter;
import com.google.refine.importers.ImporterUtilities.MultiFileReadingProgress;
import com.google.refine.importers.SeparatorBasedImporter;
import com.google.refine.importing.ImportingJob;
import com.google.refine.importing.ImportingManager;
import com.google.refine.model.AbstractOperation;
import com.google.refine.model.Project;
import com.google.refine.operations.OperationRegistry;
import com.google.refine.process.Process;

import edu.mit.simile.butterfly.ButterflyModule;
import eu.spaziodati.batchrefine.core.ITransformEngine;

public class TransformEngineImpl implements ITransformEngine {

	private static final Logger fLogger = LoggerFactory
			.getLogger(TransformEngineImpl.class);

	private RefineServlet fServletStub;

	public TransformEngineImpl init() throws IOException {
		RefineServlet servlet = new RefineServletStub();
		fServletStub = servlet;

		ProjectManagerStub.initialize();
		ImportingManager.initialize(servlet);

		registerOperations();

		return this;
	}

	@Override
	public void transform(File original, JSONArray transform,
			OutputStream transformed) throws IOException, JSONException {
		ensureInitialized();

		Project project = loadData(original);

		applyTransform(project, transform);

		outputResults(project, transformed);
	}

	private Project loadData(File original) throws IOException {
		// Creates project. Project contain the RecordModel
		// and ColumnModel instances, which contain the actual data.
		Project project = new Project();
		ProjectMetadata metadata = new ProjectMetadata();

		// Engine will try to access project by calling this singleton
		// later, so we have to register it.
		ProjectManager.singleton.registerProject(project, metadata);

		// Imports, i.e. loads a CSV into the engine. This is a
		// bureaucratic process.

		// 1. The importer requires a "job".
		ImportingJob job = ImportingManager.createJob();
		job.getOrCreateDefaultConfig();

		ensureFileInLocation(original, job.getRawDataDir());

		// 2. and a "file record" with things like the file format.
		// The engine would normally get that from the browser.
		JSONObject fileRecord = createFileRecord(original,
				"text/line-based/*sv");

		// Creates the CSV importer.
		SeparatorBasedImporter importer = new SeparatorBasedImporter();

		// 3. we have to call this method to initialize the "options" object,
		// which contains some more configuration options to the importer.
		JSONObject options = importer.createParserUIInitializationData(job,
				asList(fileRecord), "text/line-based/*sv");

		List<Exception> exceptions = new ArrayList<Exception>();

		// 4. finally we can import.
		importer.parseOneFile(project, metadata, job, fileRecord, -1, options,
				exceptions, NULL_PROGRESS);

		// After import we have to call update (which is a post-load
		// initialization procedure, really) or the row model will be
		// inconsistent.
		project.update();

		if (fLogger.isDebugEnabled()) {
			fLogger.debug("Loaded file " + original.getName() + " with "
					+ project.rows.size() + " rows.");
		}

		return project;
	}

	private void applyTransform(Project project, JSONArray transform)
			throws JSONException {

		for (int i = 0; i < transform.length(); i++) {
			AbstractOperation operation = OperationRegistry.reconstruct(
					project, transform.getJSONObject(i));

			if (operation != null) {
				try {
					Process process = operation.createProcess(project,
							new Properties());

					project.processManager.queueProcess(process);
				} catch (Exception ex) {
					fLogger.error("Error applying operation.", ex);
				}
			}
		}
	}

	private void outputResults(Project project, OutputStream transformed)
			throws IOException {
		CsvExporter exp = new CsvExporter();
		exp.export(project, null, new Engine(project), new OutputStreamWriter(
				transformed));
	}

	private void ensureFileInLocation(File original, File rawDataDir)
			throws IOException {
		// Is this where the refine engine expects to find it?
		if (isParent(original, rawDataDir)) {
			return;
		}

		// No, have to put it there.
		FileUtils.copyFile(original, new File(rawDataDir, original.getName()));
	}

	private boolean isParent(File original, File rawDataDir) throws IOException {
		File parentFolder = original.getAbsoluteFile().getParentFile();
		if (parentFolder == null) {
			// Well, this should not happen, so I reserve the right to
			// produce a crappy error message.
			throw new IOException("Can't obtain a parent path for "
					+ original.getAbsolutePath() + ".");
		}

		return rawDataDir.getAbsoluteFile().equals(original);
	}

	/**
	 * This method runs part of the core module controller.js script so that we
	 * can register all operations without having to duplicate configuration
	 * here.
	 */
	private void registerOperations() throws IOException {
		ButterflyModule core = new ButterflyModuleStub("core");

		File coreController = new File(Configurations.get("refine.root",
				"../OpenRefine"),
				"main/webapp/modules/core/MOD-INF/controller.js");

		if (!coreController.exists()) {
			fLogger.warn("Can't find core module controller - core operations can't be initialized.");
			return;
		}

		// Compiles and "executes" the controller script. The script basically
		// contains function declarations.
		Context context = ContextFactory.getGlobal().enterContext();
		Script controller = context.compileReader(
				new FileReader(coreController), "init.js", 1, null);

		// Initializes the scope.
		ScriptableObject scope = new ImporterTopLevel(context);
		scope.put("module", scope, core);
		controller.exec(context, scope);

		// Runs the function that initializes the OperationRegistry.
		try {
			Object fun = context.compileString("registerOperations", null, 1,
					null).exec(context, scope);
			if (fun != null && fun instanceof Function) {
				try {
					((Function) fun).call(context, scope, scope,
							new Object[] {});
				} catch (EcmaError ee) {
					fLogger.error("Error running core moduler controller.", ee);
				}
			}
		} catch (EcmaError ex) {
			// ignore.
		}
	}

	private JSONObject createFileRecord(File original, String format) {

		JSONObject fileRecord = new JSONObject();

		try {
			fileRecord.put("declaredMimeType", "text/csv");
			fileRecord.put("location", original.getName());
			fileRecord.put("fileName", original.getName());
			fileRecord.put("origin", "upload");
			fileRecord.put("format", format);
			fileRecord.put("size", original.length());
		} catch (JSONException ex) {
			throw new RuntimeException("Internal error.", ex);
		}

		return fileRecord;
	}

	private List<JSONObject> asList(JSONObject object) {
		ArrayList<JSONObject> list = new ArrayList<JSONObject>();
		list.add(object);
		return list;
	}

	private void ensureInitialized() {
		if (fServletStub == null) {
			throw new IllegalStateException("Engine needs to be initialized.");
		}
	}

	private static final MultiFileReadingProgress NULL_PROGRESS = new MultiFileReadingProgress() {
		@Override
		public void startFile(String fileSource) {
		}

		@Override
		public void readingFile(String fileSource, long bytesRead) {
		}

		@Override
		public void endFile(String fileSource, long bytesRead) {
		}
	};

}
