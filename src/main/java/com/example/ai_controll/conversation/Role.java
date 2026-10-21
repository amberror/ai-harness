package com.example.ai_controll.conversation;

/** The four roles the harness can put into a transcript. */
public enum Role {

	SYSTEM, USER, ASSISTANT, TOOL;

	/** Lower-case form used on the wire. */
	public String wireValue() {
		return name().toLowerCase();
	}

	public static Role fromWire(String value) {
		return value == null ? ASSISTANT : Role.valueOf(value.trim().toUpperCase());
	}
}
