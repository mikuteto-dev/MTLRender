package dev.mikuteto.mtlrender.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackListModelTest {
	@TempDir
	Path directory;

	@Test
	void listsOnlyShaderPackCandidatesInStableOrder() throws IOException {
		writeZip("zeta.zip", "shaders/final.fsh");
		writeZip("alpha.zip", "shaders/gbuffers.fsh");
		Files.createDirectories(directory.resolve("folder-pack/shaders"));
		Files.createDirectories(directory.resolve("not-a-pack"));
		Files.writeString(directory.resolve("notes.txt"), "not a pack");
		writeZip("broken.zip", null);

		PackListModel model = new PackListModel(directory);

		assertEquals(List.of("", "alpha.zip", "broken.zip", "folder-pack", "zeta.zip"),
			model.entries().stream().map(PackListModel.Entry::id).toList());
		assertEquals("Off", model.entries().getFirst().displayName());
		assertEquals(directory.resolve("folder-pack"), model.entries().get(3).path());
		assertEquals(directory, model.openFolder());
		assertFalse(model.entries().stream().anyMatch(entry -> entry.id().equals("not-a-pack")));
	}

	@Test
	void keepsMalformedZipAsSelectableCandidate() throws IOException {
		Files.write(directory.resolve("broken.zip"), new byte[] {1, 2, 3, 4});
		PackListModel model = new PackListModel(directory);

		assertTrue(model.select("broken.zip"));
		assertEquals("broken.zip", model.stagedSelection());
		assertTrue(model.select(""));
		assertEquals("", model.stagedSelection());
	}

	private void writeZip(String name, String entryName) throws IOException {
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(directory.resolve(name)))) {
			if (entryName != null) {
				output.putNextEntry(new ZipEntry(entryName));
				output.write("void main() {}".getBytes());
				output.closeEntry();
			}
		}
	}
}
