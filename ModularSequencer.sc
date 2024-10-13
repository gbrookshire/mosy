// SuperCollider Step Sequencer for Modular Synth

CVSequencer {
	var <numTracks, <numPatternsPerTrack, <testMode;
	var <clock, <tracks, <gui, <currentState;

	*new { |numTracks = 4, numPatternsPerTrack=4, testMode=false|
		^super.newCopyArgs(
			numTracks, numPatternsPerTrack, testMode
		).init;
	}

	init {
		clock = TempoClock.new(2);  // 120 BPM  // FIXME is this right?
		tracks = Array.fill(numTracks, { CVTrack.new(this) });
		if(
			testMode,
			{ this.initTestSynth },
			{ this.initCVSynths }
		);
		currentState = ();
		this.initGUI();
	}

	// Define a synth for testing
	initTestSynth {
		"Initializing test synth".postln;
		SynthDef(\testSine, {
			|freq = 440, amp = 0.1, gate = 1|
			var env = EnvGen.kr(Env.perc(0.01, 0.5), doneAction: 2);
			var sig = SinOsc.ar(freq) * env * amp;
			Out.ar(0, sig!2);
		}).add;
	}

	// Initialize the synths for each track to send control voltage
	initCVSynths {
		tracks.do({_.initCVSynth});
		// FIXME \clockSynth  Make a master clock?
	}

	// Initialize the GUI
	initGUI {
		gui = CVSequencerGUI(this);
	}

	// Start playing a given track
	start { |trackIndex, quantize = \immediate|
		tracks[trackIndex].start(quantize);
		gui.updatePlayStopButton(trackIndex, true);
	}

	// Stop playing a given track
	stop { |trackIndex, quantize = \immediate|
		tracks[trackIndex].stop(quantize);
		gui.updatePlayStopButton(trackIndex, false);
	}

	// Save the state of the seqeuncer and write to a file
	saveState { |filepath|
		currentState = (
			numTracks: numTracks,
			tracks: tracks.collect(_.getState),
			tempo: clock.tempo
		);
		currentState.writeArchive(filepath);
	}

	// Load a saved sequencer state from a file
	loadState { |filepath|
		var loadedState = Object.readArchive(filepath);
		numTracks = loadedState.numTracks;
		clock.tempo = loadedState.tempo;
		tracks = loadedState.tracks.collect { |trackState|
			CVTrack.new(this).setState(trackState);
		};
		gui.refresh;
	}
}

CVTrack {
	var <sequencer, <patterns, <>currentPattern;
	var <isPlaying;
	var cvSynth, <cvOut, <gateOut;
	var routine;

	*new { |sequencer|
		^super.newCopyArgs(sequencer).init;
	}

	init {
		patterns = Array.fill(
			sequencer.numPatternsPerTrack,
			{ CVPattern.new }
		);
		currentPattern = 0;
		cvOut = 0;
		gateOut = 1;
		isPlaying = false;
	}

	// Private helper to allow us to specify output channels as they're
	// written on the faceplate of the ES-9.
	// FIXME Implement this in the functions to create the CV synth
	prOutputChannelsForES9 { |out|
		^out+7
	}

	// Initialize the synth that will give CV pulses
	initCVSynth {
		cvSynth = EuroCVSynth(cvOut: cvOut, gateOut: gateOut);
	}

	cvOut_ { |x|
		cvOut = x;
		// FIXME Just update the attribute instead of creating a new object
		cvSynth = EuroCVSynth(cvOut: cvOut, gateOut: gateOut);
	}

	gateOut_ { |x|
		gateOut = x;
		// FIXME Just update the attribute instead of creating a new object
		cvSynth = EuroCVSynth(cvOut: cvOut, gateOut: gateOut);
	}

	// Start playing this track
	start { |quantize|
		var startFunc = {
			isPlaying = true;
			this.playCurrentPattern;
		};

		switch(quantize,
			\immediate, { startFunc.value },
			\nextBeat, {
				sequencer.clock.schedAbs(
					sequencer.clock.nextTimeOnGrid(1),
					startFunc)
			},
			\masterTrack, {
				// FIXME Implement synchronization with master track
			}
		);
	}

	// Play a single note in test mode using the simple testing synth
	prPlayStepTestMode { |stepValue|
		var activePattern = patterns[currentPattern];
		var scale = activePattern.scale;
		// FIXME to incorporate tunings use:
		// activePattern.scale(activePattern.tuning);
		var freq = scale.degreeToFreq(
			stepValue, rootFreq: 200, octave: 0
		);
		("Playing in test mode: " ++ stepValue).postln;
		Synth(
			\testSine,
			[
				\freq, freq,
				\amp, 0.1,
				\gate, 1
			]
		);
	}

	// Play a single step: set the CV output and send a gate trigger
	prPlayStep { |stepValue|
		var activePattern = patterns[currentPattern];
		var event;
		// FIXME to incorporate different tunings use:
		//     activePattern.scale(activePattern.tuning)
		var cvValue = ( // Convert from chromatic scale degree to ES-9 output
			activePattern.scale
			.degreeToRatio(stepValue, octave: 0) // Freq multiplier relative to base VCO frequency
			.log2 // Convert to V/oct: +1 oct == +1 V
			/ 10 // Scale to the Voltage limits of the ES-9
		);

		event = (
			type: \eurocv,
			euro: cvSynth,
			amp: cvValue,
			// sustain: 1  // How long the gate stays high // FIXME implement
			// legato: 0  // Portamento  // FIXME implement
		);
		event.play;
	}

	// Start playing the selected pattern on this track
	playCurrentPattern {
		var beatDuration = sequencer.clock.beatDur;

		if(isPlaying, {
			if(routine.notNil, { routine.stop });

			routine = Routine({
				var stepIndex = 0;

				inf.do {
					// FIXME only switch patterns at the beginning/end of a pattern
					var pattern = patterns[currentPattern];
					var stepValue = pattern.steps[stepIndex];
					var dur = (beatDuration * pattern.tempoMultiplier);

					// FIXME Delete this mini-block?
					var scale;
					scale = pattern.scale.contentsCopy;
					scale.tuning = pattern.tuning;

					if(
						sequencer.testMode,
						{ this.prPlayStepTestMode(stepValue) },
						{ this.prPlayStep(stepValue) }
					);

					dur.wait;
					stepIndex = (stepIndex + 1) % pattern.nSteps;
				}
			});

			sequencer.clock.schedAbs(sequencer.clock.nextBar, {
				routine.play(sequencer.clock);
			});
		});
	}

	// Stop playing this track
	stop { |quantize|
		var stopFunc = {
			isPlaying = false;
			if(routine.notNil, { routine.stop });
		};

		switch(quantize,
			\immediate, { stopFunc.value },
			\nextBeat, { sequencer.clock.schedAbs(sequencer.clock.nextTimeOnGrid(1), stopFunc) },
			\masterTrack, {
				// Implement synchronization with master track
			}
		);
	}

	getState {
		^(
			patterns: patterns.collect(_.getState),
			currentPattern: currentPattern,
			cvOut: cvOut,
			gateOut: gateOut,
			isPlaying: isPlaying
		)
	}

	setState { |state|
		patterns = state.patterns.collect { |patternState|
			CVPattern.new.setState(patternState)
		};
		currentPattern = state.currentPattern;
		cvOut = state.cvOut;
		gateOut = state.gateOut;
		isPlaying = state.isPlaying;
	}
}

CVPattern {
	var <>steps, <nSteps, <>scale, <>tuning, <>tempoMultiplier;
	var <minVal, <maxVal, <>cvQuantizationStep;

	*new { |nSteps = 16, minVal = 0, maxVal = 30, cvQuantizationStep = 1|
		^super.new.init(nSteps, minVal, maxVal, cvQuantizationStep);
		// FIXME replace with ^super.newCopyArgs(.......).init;
	}

	init { |argLength, argMinVal, argMaxVal, argCvQuantizationStep|
		nSteps = argLength;
		minVal = argMinVal;
		maxVal = argMaxVal;
		cvQuantizationStep = argCvQuantizationStep;
		steps = Array.fill(nSteps, { 0 });
		scale = Scale.major;
		tuning = Tuning.et12;
		tempoMultiplier = 1;
	}

	// Set the length of the pattern, cutting or filling in steps as needed
	nSteps_ { |n|
		n = n.asInteger;
		if (n > nSteps) {
			steps = steps ++ (0!(n - nSteps))
		} {
			steps = steps[0..n]; // FIXME Is this necessary?
		};
		nSteps = n;
	}

	// Make sure you can't lose part of the sequence by changing the min value
	minVal_ { |x|
		minVal = minItem([x] ++ steps);
	}

	// Make sure you can't lose part of the sequence by changing the max value
	maxVal_ { |x|
		maxVal = maxItem([x] ++ steps);
	}

	getState {
		^(
			steps: steps,
			nSteps: nSteps,
			scale: scale,
			tuning: tuning,
			tempoMultiplier: tempoMultiplier,
			minVal: minVal,
			maxVal: maxVal,
			cvQuantizationStep: cvQuantizationStep
		)
	}

	setState { |state|
		steps = state.steps;
		nSteps = state.nSteps;
		scale = state.scale;
		tuning = state.tuning;
		tempoMultiplier = state.tempoMultiplier;
		minVal = state.minVal;
		maxVal = state.maxVal;
		cvQuantizationStep = state.cvQuantizationStep;
	}
}

CVSequencerGUI {
	var <sequencer, <window, <trackButtons, <playStopButtons, <tempoBox;
	var <currentTrackIndex, <currentPatternIndex;
	var <cvEditor, <patternParamControls, <trackParamControls;

	*new { |sequencer|
		^super.newCopyArgs(sequencer).init;
	}

	init {
		currentTrackIndex = 0;
		currentPatternIndex = 0;
		this.createWindow();
		this.createViews();
	}

	getActivePattern {
		^sequencer.tracks[currentTrackIndex].patterns[currentPatternIndex]
	}

	createWindow {
		var windowSize = [700, 500];

		window = Window(
			"Step Sequencer",
			Rect(
				left: 200,
				top: Window.screenBounds.height - 550,
				width: windowSize[0],
				height: windowSize[1]
			)
		).front;
	}

	createViews {
		var layout = VLayout();
		var controlPanel = HLayout();
		var cvEditorPanel = VLayout();
		var activePattern = this.getActivePattern;
		var patternParamNames = [ // Numeric params for a CVPattern
			'nSteps',
			'minVal',
			'maxVal',
			'cvQuantizationStep'
		];
		var trackParamNames = [ // Numeric params for a CVTrack
			'cvOut',
			'gateOut'
		];

		// Select the active track
		controlPanel.add(StaticText().string_("Track:"));
		controlPanel.add(
			ListView()
			.fixedSize_(Size(30, 20))
			.items_((0..(sequencer.numTracks - 1)))
			.background_(Color.clear)
			.hiliteColor_(Color.grey(alpha:0.6))
			.value_(currentTrackIndex)
			.action_({ arg lst;
				currentTrackIndex = lst.value;
				activePattern = this.getActivePattern;
				this.updateCVEditor;
			})
		);
		// Select the active pattern within the track
		controlPanel.add(StaticText().string_("Pattern:"));
		controlPanel.add(
			ListView()
			.fixedSize_(Size(30, 20))
			.items_((0..(sequencer.numPatternsPerTrack - 1)))
			.background_(Color.clear)
			.hiliteColor_(Color.grey(alpha:0.6))
			.value_(currentPatternIndex)
			.action_({ arg lst;
				currentPatternIndex = lst.value;
				sequencer.tracks[currentTrackIndex].currentPattern = currentPatternIndex;
				activePattern = this.getActivePattern;
				this.updateCVEditor;
			})
		);

		// Play/Stop buttons and Tempo control
		playStopButtons = sequencer.numTracks.collect { |i|
			Button()
			.maxWidth_(30)
			.states_([
				["▶️ " ++ i, Color.black, Color.white],
				["⏹️ " ++ i, Color.white, Color.black]
			])
			.action_({ |but|
				if(but.value == 1) {
					sequencer.start(i);
				} {
					sequencer.stop(i);
				};
			})
			.value_(0);
		};

		tempoBox = NumberBox()
		.value_(120)
		.action_({ |nb|
			sequencer.clock.tempo = nb.value / 60;
		});

		playStopButtons.do { |button|
			controlPanel.add(button);
		};
		controlPanel.add(StaticText().string_("Tempo:"));
		controlPanel.add(tempoBox);

		// Save/load
		controlPanel.add(
			Button()
			.string_("Save as")
			.action_({ |but|
				var fileName;
				FileDialog(
					{ | path |
						sequencer.saveState(path);
					},
					fileMode: 0,
					acceptMode: 1,
					stripResult: true
				)
			})
		);
		controlPanel.add(
			Button()
			.string_("Open")
			.action_({ |but|
				var fileName;
				FileDialog(
					{ | path |
						sequencer.loadState(path);
					},
					fileMode: 1,
					acceptMode: 0,
					stripResult: true
				)
			})
		);

		// CV Editor
		cvEditor = MultiSliderView()
		.size_(activePattern.nSteps)
		.value_(activePattern.steps.linlin(
			activePattern.minVal,
			activePattern.maxVal,
			0,
			1,
		)
		)
		.step_( // Quantize the display
			activePattern.cvQuantizationStep
			/ (activePattern.maxVal - activePattern.minVal)
		)
		.elasticMode_(1)
		.valueThumbSize_(4) // Height of the bars
		.indexThumbSize_( // Width of the bars
			(window.bounds.width + (2 * activePattern.nSteps))
			/ activePattern.nSteps
		)
		.action_({ |ms|
			activePattern.steps = ms.value.linlin(
				0,
				1,
				activePattern.minVal,
				activePattern.maxVal
			);
		});

		// Make controls to edit some parameters for the track pattern
		patternParamControls = Dictionary.new;
		patternParamNames.do({ |fieldName|
			patternParamControls.put(
				fieldName, // Create controls named after the parameter
				// Make an editable number box to set the attrib
				// in the active pattern.
				NumberBox().action_({ |nb|
					activePattern.perform(
						(fieldName ++ "_").asSymbol,
						nb.value
					);
					this.updateCVEditor;
				})
			)
		});

		// Add a couple of controls that apply to the whole track
		trackParamControls = Dictionary.new;
		trackParamNames.do({ |fieldName|
			trackParamControls.put(
				fieldName,
				// Make an editable number box to set the attrib
				// in the active track.
				NumberBox().action_({ |nb|  // With an editable number box
					// That sets this attrib in the active track
					sequencer.tracks[currentTrackIndex].perform(
						(fieldName ++ "_").asSymbol,
						nb.value
					);
					this.updateCVEditor;
				})
			)
		});

		cvEditorPanel.add(HLayout(

			// Parameter controls for this track pattern
			StaticText().string_("Steps:"), patternParamControls[\nSteps],
			StaticText().string_("Min:"), patternParamControls[\minVal],
			StaticText().string_("Max:"), patternParamControls[\maxVal],
			StaticText().string_("Quant:"), patternParamControls[\cvQuantizationStep],

			// Add a scale picker
			StaticText().string_("Scale:"),
			TextField()
			.value_(activePattern.scale.name.toLower)
			.action_({ arg field;
				var scaleName = field.value.toLower.asSymbol;
				if (
					Scale.at(scaleName).isNil,
					{
						(
							"Scale '" ++ scaleName ++ "' not recognized. "
							++ "Setting scale to 'major'. "
							++ "See 'Scale.directory' for options."
						).postln;
						scaleName = "major";
						field.value = scaleName;
					},
					{ },
				);
				activePattern.scale = Scale.at(scaleName);
				this.updateCVEditor;
			}),

			// // Add a tuning picker
			// StaticText().string_("Tuning:"),
			// TextField()
			// .value_(activePattern.tuning.name.toLower)
			// .action_({ arg field;
			// 	var tuningName = field.value.toLower.asSymbol;
			// 	if (
			// 		Tuning.at(tuningName).isNil,
			// 		{
			// 			(
			// 				"Tuning '" ++ tuningName ++ "' not recognized. "
			// 				++ "Setting tuning to 'et12'. "
			// 				++ "See 'Tuning.directory' for options."
			// 			).postln;
			// 			tuningName = "et12";
			// 			field.value = tuningName;
			// 		},
			// 		{ },
			// 	);
			// 	activePattern.tuning = Tuning.at(tuningName);
			// 	this.updateCVEditor;
			// }),

			// Parameter controls for the track
			StaticText().string_("CV bus:"), trackParamControls[\cvOut],
			StaticText().string_("Gate bus:"), trackParamControls[\gateOut]

		));

		cvEditorPanel.add(cvEditor);

		layout.add(controlPanel);
		layout.add(cvEditorPanel);

		window.layout = layout;
		this.updateCVEditor;
	}

	updateCVEditor {
		var activePattern = this.getActivePattern;
		cvEditor.value = activePattern.steps.linlin(
			activePattern.minVal,
			activePattern.maxVal,
			0,
			1,
		);
		cvEditor.size = activePattern.nSteps;
		cvEditor.step = ( // Quantize the display
			activePattern.cvQuantizationStep
			/ (activePattern.maxVal - activePattern.minVal)
		);
		// Update the number boxes with values from the active pattern/track
		patternParamControls.keysValuesDo({ |fieldName, numBox|
			numBox.value = activePattern.perform(fieldName);
		});
		trackParamControls.keysValuesDo({ |fieldName, numBox|
			numBox.value = sequencer.tracks[currentTrackIndex].perform(fieldName);
		});
	}

	updatePlayStopButton { |trackIndex, isPlaying|
		{
			playStopButtons[trackIndex].value = isPlaying.asInteger;
		}.defer;
	}


	refresh {
		tempoBox.value = sequencer.clock.tempo * 60;
		this.updateCVEditor;
	}
}