/*
Adapted from EuroCollider.sc by Geoff Brookshire

Small changes to that file enable us to send control
signals as voltage without needing to tune the oscillator.

TODO
- Enter CV changes directly as voltage
- Clear out old code
*/

EuroCVSynth {
	var <cvOut;
	var <gateOut;
	var <controlSynth;

	*new {|cvOut, gateOut|
		^super.newCopyArgs(
			cvOut,
			gateOut,
		).init;
	}

	*initClass {
		StartUp.add({
			EuroCVSynth.addEventType;
		});
	}

	init {
		// if gateOut is nil we will create a bus to dump it to somewhere else
		// shall this get released on clear?
		gateOut = gateOut ? Bus.audio(Server.default, 1).index;
		// same for cvOut which may be necessary in case your interface is non-dc coupled
		cvOut = cvOut ? Bus.audio(Server.default, 1).index;

		this.startControlSynth;
		CmdPeriod.add({{this.startControlSynth}.defer(0.1)});
	}

	startControlSynth {
		controlSynth = SynthDef(\EuroColliderCVSynth, {|cvOut, gateOut, dcOffset=0, gate=0|
			var env = EnvGen.ar(
				envelope: Env.asr(attackTime: 0.001, releaseTime: 0.001),
				gate: gate,
			);
			Out.ar(cvOut, DC.ar(1.0) * dcOffset);
			Out.ar(gateOut, env);
		}).play(args: [
			\cvOut, cvOut,
			\gateOut, gateOut,
		]).register;
	}

	*addEventType {
		Event.addEventType(\eurocv, {
			var euroCVSynth = ~euro.value;
			if((euroCVSynth.class == EuroCVSynth).not, {
				"Add a EuroCVSynth as \"euro\" parameter to your event".postln;
			});
			if(euroCVSynth.controlSynth.isPlaying.not, {
				"%: Control synth is not running".format(euroCVSynth).postln;
			}, {
				euroCVSynth.controlSynth.set(
					\dcOffset, ~amp;
				);
				euroCVSynth.controlSynth.set(
					\gate, 1.0,
				);
				// delay the gate down signal by sustain value
				{euroCVSynth.controlSynth.set(
					\gate, 0.0,
				)}.defer(~sustain.value);
			});
		});
	}

	clear {
		controlSynth.free;
	}

	printOn { | stream |
		stream << "EuroCVSynth(cvOut: " << cvOut << ", gateOut: " << gateOut << ")";
	}
}

