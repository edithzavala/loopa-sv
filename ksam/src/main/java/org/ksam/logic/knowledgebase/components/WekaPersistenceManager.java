package org.ksam.logic.knowledgebase.components;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.ksam.model.configuration.MeConfig;
import org.ksam.model.configuration.monitors.Monitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.reactivex.subjects.PublishSubject;

public class WekaPersistenceManager {
    protected final Logger LOGGER = LoggerFactory.getLogger(getClass().getName());
    private IContextPersister ctxPersister;
    private Map<String, Monitor> monitors;
    private Map<String, String> pMonsVarsRuntimeData;
    private Map<String, String> contextVarsValues;
    private List<String> persistenceMonitors;
    private String meId;

    private final String filePath;
    private PublishSubject<Map<String, Double>> dataToPersistInArff;
    private final ExecutorService arffFilePersister = Executors.newSingleThreadExecutor();
    // private final MonVarsRangesTranslator mvrT;
    private List<String> inactiveMons;

    private final PositionPersistenceManager pm; // Specific to opendlv

    public WekaPersistenceManager(MeConfig config) {
	super();
	this.pm = new PositionPersistenceManager(config);
	this.meId = config.getSystemUnderMonitoringConfig().getSystemId();
	// this.mvrT = new MonVarsRangesTranslator(monVars);
	this.persistenceMonitors = config.getSystemUnderMonitoringConfig().getSystemConfiguration().getMonitorConfig()
		.getPersistenceMonitors();
	this.filePath = "/tmp/weka/" + this.meId + "_real.arff";
	this.pMonsVarsRuntimeData = new HashMap<>();
	this.monitors = new HashMap<>();
	config.getSystemUnderMonitoringConfig().getSystemConfiguration().getMonitorConfig().getMonitors().forEach(m -> {
	    monitors.put(m.getMonitorAttributes().getMonitorId(), m);
	});
	this.contextVarsValues = new HashMap<>();
	config.getSystemUnderMonitoringConfig().getSystemVariables().getContextVars().getStates()
		.forEach(var -> this.contextVarsValues.put(var, "0"));
	this.ctxPersister = new OpenDlvContextPersister(
		config.getSystemUnderMonitoringConfig().getSystemVariables().getContextVars().getStates());
	createHeader();
	this.inactiveMons = new ArrayList<>();
	this.dataToPersistInArff = PublishSubject.create();
	this.dataToPersistInArff.subscribe(monVarData -> arffFilePersister.execute(() -> {
	    this.pMonsVarsRuntimeData.forEach((k, v) -> {
		if (monVarData.keySet().contains(k)) {
		    // this.pMonsVarsRuntimeData.put(k, this.mvrT.getValueRange(k.split("-")[1],
		    // monVarData.get(k)));
		    this.pMonsVarsRuntimeData.put(k, monVarData.get(k) < 0 ? "?" : String.valueOf(monVarData.get(k)));
		}
		if (this.inactiveMons.contains(k.split("-")[0])) {
		    this.pMonsVarsRuntimeData.put(k, "?");
		}
	    });
	    this.pm.setPoint(this.pMonsVarsRuntimeData);
	    setToArffFile("\n"
		    + this.pMonsVarsRuntimeData.values().toString()
			    .substring(1, this.pMonsVarsRuntimeData.values().toString().length() - 1).replace(" ", "")
		    + ","
		    + this.contextVarsValues.values().toString()
			    .substring(1, this.contextVarsValues.values().toString().length() - 1).replace(" ", ""),
		    true);
	}));

    }

    public void setMonitoringData(Map<String, Double> monVarValue) {
	this.dataToPersistInArff.onNext(monVarValue);
    }

    public void setToArffFile(String stringToWrite, boolean append) {
	// LOGGER.info("Data to persist in arff: " + stringToWrite);
	try {
	    File file = new File(this.filePath);
	    if (!file.exists()) {
		file.createNewFile();
	    }

	    FileWriter fileWritter = new FileWriter(file, append);
	    BufferedWriter output = new BufferedWriter(fileWritter);
	    output.write(stringToWrite);
	    output.close();
	} catch (IOException e) {
	    e.printStackTrace();
	}
    }

    public void createHeader() {
	this.persistenceMonitors.forEach(am -> {
	    this.monitors.get(am).getMonitorAttributes().getMonitoringVars()
		    .forEach(var -> this.pMonsVarsRuntimeData.put(am + "-" + var, "?"));
	});

	String header = "@relation " + this.meId + "\n\n";

	for (String monVar : this.pMonsVarsRuntimeData.keySet()) {
	    // header += "@attribute " + monVar + " " +
	    // this.mvrT.getVarRanges(monVar.split("-")[1]) + "\n";
	    header += "@attribute " + monVar + " numeric\n";
	}

	for (String ctxVar : this.contextVarsValues.keySet()) {
	    header += "@attribute " + ctxVar + " {0,1}\n";
	}

	header += "\n@data";
	setToArffFile(header, false);
    }

    public void updateActiveMonitors(List<String> activeMonitors) {
	this.persistenceMonitors.forEach(m -> {
	    if (!activeMonitors.contains(m)) {
		this.inactiveMons.add(m);
	    }
	});
    }

    public Map<String, Integer> setContextData(List<Entry<String, Object>> context) {
	Map<String, Integer> cxtVarVal = this.ctxPersister.updateContext(context);
	cxtVarVal.forEach((k, v) -> this.contextVarsValues.put(k, String.valueOf(v)));
	return cxtVarVal;
    }

}
