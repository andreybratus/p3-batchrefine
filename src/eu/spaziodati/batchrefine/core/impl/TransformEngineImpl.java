package eu.spaziodati.batchrefine.core.impl;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.refine.ProjectMetadata;
import com.google.refine.RefineServlet;
import com.google.refine.importers.ImporterUtilities.MultiFileReadingProgress;
import com.google.refine.importers.SeparatorBasedImporter;
import com.google.refine.importing.ImportingJob;
import com.google.refine.importing.ImportingManager;
import com.google.refine.model.AbstractOperation;
import com.google.refine.model.Project;
import com.google.refine.operations.OperationRegistry;
import com.google.refine.process.Process;

import eu.spaziodati.batchrefine.core.ITransformEngine;

public class TransformEngineImpl implements ITransformEngine {

	private RefineServlet fServletStub;

	public TransformEngineImpl init() {
		RefineServlet servlet = new RefineServletStub();
		fServletStub = servlet;
		ImportingManager.initialize(servlet);

		return this;
	}

	@Override
	public void transform(File original, JSONArray transform,
			OutputStream transformed) throws IOException, JSONException {

		ensureInitialized();

		Project project = loadData(original);

		applyTransform(project, transform);

	}

	private Project loadData(File original) throws IOException {
		ImportingJob job = ImportingManager.createJob();
		job.getOrCreateDefaultConfig();

		ensureFileInLocation(original, job.getRawDataDir());

		JSONObject fileRecord = createFileRecord(original,
				"text/line-based/*sv");

		SeparatorBasedImporter importer = new SeparatorBasedImporter();

		// Creates project and job.
		Project project = new Project();
		ProjectMetadata metadata = new ProjectMetadata();

		List<Exception> exceptions = new ArrayList<Exception>();

		JSONObject options = importer.createParserUIInitializationData(job,
				asList(fileRecord), "text/line-based/*sv");

		importer.parseOneFile(project, metadata, job, fileRecord, -1, options,
				exceptions, NULL_PROGRESS);

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
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}

	private void ensureFileInLocation(File original, File rawDataDir)
			throws IOException {
		// Is this where the refine engine expects to find it?
		if (original.getParentFile().equals(rawDataDir)) {
			return;
		}

		// No, have to put it there.
		FileUtils.copyFile(original, new File(rawDataDir, original.getName()));
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