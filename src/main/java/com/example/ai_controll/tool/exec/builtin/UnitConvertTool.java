package com.example.ai_controll.tool.exec.builtin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.ai_controll.tool.exec.ToolInvocation;
import com.example.ai_controll.tool.exec.ToolOutcome;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Converts a value between units of the same kind.
 *
 * <p>Chosen for its argument shape as much as its usefulness: it is the harness's one tool with
 * <strong>enumerated</strong> arguments. A small model that handles free strings comfortably can
 * still invent {@code "metres"} where the schema says {@code "m"}, and whether an {@code enum}
 * constraint in the schema prevents that is exactly the kind of question this harness exists to
 * answer -- with grammar-constrained decoding as the obvious variant to compare against.
 *
 * <p>Temperature is handled apart from everything else because it is affine rather than
 * multiplicative: 0°C is not zero of anything, so a scale factor cannot express it.
 */
@Component
public class UnitConvertTool implements BuiltinTool {

	/** Unit to (kind, how many base units it is worth). Base units: metre, gram, second, byte. */
	private static final Map<String, Unit> UNITS = new LinkedHashMap<>();

	static {
		register("mm", "length", 0.001);
		register("cm", "length", 0.01);
		register("m", "length", 1);
		register("km", "length", 1000);
		register("in", "length", 0.0254);
		register("ft", "length", 0.3048);
		register("yd", "length", 0.9144);
		register("mi", "length", 1609.344);

		register("mg", "mass", 0.001);
		register("g", "mass", 1);
		register("kg", "mass", 1000);
		register("t", "mass", 1_000_000);
		register("oz", "mass", 28.349523125);
		register("lb", "mass", 453.59237);

		register("ms", "time", 0.001);
		register("s", "time", 1);
		register("min", "time", 60);
		register("h", "time", 3600);
		register("d", "time", 86_400);

		// Decimal, not binary: kb is 1000 bytes here. Stated in the description, because the other
		// convention is equally common and a silent choice would make the answers unverifiable.
		register("b", "data", 1);
		register("kb", "data", 1_000);
		register("mb", "data", 1_000_000);
		register("gb", "data", 1_000_000_000);
		register("tb", "data", 1_000_000_000_000d);
	}

	private static void register(String symbol, String kind, double inBaseUnits) {
		UNITS.put(symbol, new Unit(kind, inBaseUnits));
	}

	private final ObjectMapper objectMapper;

	public UnitConvertTool(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public String name() {
		return "unit_convert";
	}

	@Override
	public String defaultDescription() {
		return "Converts a value between units of the same kind. Length: mm cm m km in ft yd mi. "
				+ "Mass: mg g kg t oz lb. Time: ms s min h d. Data (decimal, 1 kb = 1000 b): b kb mb gb tb. "
				+ "Temperature: c f k.";
	}

	@Override
	public String defaultSchema() {
		return """
				{"type":"object",
				 "properties":{
				   "value":{"type":"number","description":"The amount to convert"},
				   "from":{"type":"string","description":"Unit to convert from",
				     "enum":["mm","cm","m","km","in","ft","yd","mi","mg","g","kg","t","oz","lb",
				             "ms","s","min","h","d","b","kb","mb","gb","tb","c","f","k"]},
				   "to":{"type":"string","description":"Unit to convert to",
				     "enum":["mm","cm","m","km","in","ft","yd","mi","mg","g","kg","t","oz","lb",
				             "ms","s","min","h","d","b","kb","mb","gb","tb","c","f","k"]}},
				 "required":["value","from","to"],
				 "additionalProperties":false}""";
	}

	@Override
	public ToolOutcome run(ToolInvocation invocation, JsonNode arguments) {
		Optional<Double> value = Args.number(arguments, "value");
		Optional<String> from = Args.keyword(arguments, "from");
		Optional<String> to = Args.keyword(arguments, "to");

		if (value.isEmpty()) {
			return ToolOutcome.failure("invalid_arguments", Args.missing("value", "a number"), 0L);
		}
		if (from.isEmpty() || to.isEmpty()) {
			return ToolOutcome.failure("invalid_arguments",
					"'from' and 'to' are both required and must be unit symbols", 0L);
		}

		String fromUnit = from.get();
		String toUnit = to.get();

		if (isTemperature(fromUnit) || isTemperature(toUnit)) {
			if (!isTemperature(fromUnit) || !isTemperature(toUnit)) {
				return mismatch(fromUnit, toUnit);
			}
			return success(value.get(), fromUnit, toUnit, "temperature",
					convertTemperature(value.get(), fromUnit, toUnit));
		}

		Unit source = UNITS.get(fromUnit);
		Unit target = UNITS.get(toUnit);
		if (source == null || target == null) {
			// Naming what is available is what lets the model fix it on the next turn.
			return ToolOutcome.failure("unknown_unit",
					"Unknown unit in '%s' -> '%s'. Known units: %s".formatted(fromUnit, toUnit,
							String.join(", ", UNITS.keySet()) + ", c, f, k"),
					0L);
		}
		if (!source.kind().equals(target.kind())) {
			return mismatch(fromUnit, toUnit);
		}

		double converted = value.get() * source.inBaseUnits() / target.inBaseUnits();
		return success(value.get(), fromUnit, toUnit, source.kind(), converted);
	}

	private ToolOutcome success(double value, String from, String to, String kind, double result) {
		ObjectNode node = objectMapper.createObjectNode();
		node.put("value", value);
		node.put("from", from);
		node.put("to", to);
		node.put("kind", kind);
		node.put("result", Math.round(result * 1_000_000d) / 1_000_000d);
		return ToolOutcome.success(node.toString(), 0L);
	}

	private static ToolOutcome mismatch(String from, String to) {
		return ToolOutcome.failure("incompatible_units",
				"'%s' and '%s' measure different things, so one cannot be converted into the other."
						.formatted(from, to),
				0L);
	}

	private static boolean isTemperature(String unit) {
		return "c".equals(unit) || "f".equals(unit) || "k".equals(unit);
	}

	private static double convertTemperature(double value, String from, String to) {
		double celsius = switch (from) {
			case "f" -> (value - 32) * 5 / 9;
			case "k" -> value - 273.15;
			default -> value;
		};
		return switch (to) {
			case "f" -> (celsius * 9 / 5) + 32;
			case "k" -> celsius + 273.15;
			default -> celsius;
		};
	}

	private record Unit(String kind, double inBaseUnits) {
	}
}
