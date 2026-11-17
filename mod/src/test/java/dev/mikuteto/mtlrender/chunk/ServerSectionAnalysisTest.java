package dev.mikuteto.mtlrender.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServerSectionAnalysisTest {
	@Test
	void resultOwnsAnImmutableSectionList() {
		List<ServerSectionAnalysis.Section> source = new ArrayList<>();
		ServerSectionAnalysis.Section section = new ServerSectionAnalysis.Section(4, null, true, false, true, true, false, true);
		source.add(section);

		ServerSectionAnalysis.Result result = new ServerSectionAnalysis.Result(source);
		source.clear();

		assertEquals(List.of(section), result.sections());
		assertThrows(UnsupportedOperationException.class, () -> result.sections().add(section));
	}
}
