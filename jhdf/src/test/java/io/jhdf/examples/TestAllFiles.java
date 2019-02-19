package io.jhdf.examples;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.lang.reflect.Array;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

import io.jhdf.HdfFile;
import io.jhdf.api.Dataset;
import io.jhdf.api.Group;
import io.jhdf.api.Node;
import io.jhdf.api.NodeType;

/**
 * This is a "catch all" test designed to look at all the test HDF5 files and
 * fully explore the tree in all of them. In doing so it exercises most of the
 * jHDF code and validates the behaviour of groups and datasets.
 * 
 * @author James Mudd
 */
public class TestAllFiles {

	private static final PathMatcher hdf5 = FileSystems.getDefault().getPathMatcher("glob:**.hdf5");

	@TestFactory
	public Stream<DynamicNode> allHdf5TestFiles() throws Exception {

		// Auto discover the test files assuming they exist in under the directory
		// containing test_file.hdf5
		URL resource = this.getClass().getResource("../test_file.hdf5");
		Path path = Paths.get(resource.toURI()).getParent();
		List<Path> files = Files.walk(path).filter(hdf5::matches).collect(Collectors.toList());

		// Check at least some files have been discovered
		assertThat("Less than 3 HDF5 test files discovered searched paths below: " + path.toAbsolutePath(),
				files.size(), is(greaterThan(2)));

		// Make a test for each file
		return files.stream().map(this::createTest);
	}

	private DynamicNode createTest(Path path) {
		return dynamicTest(path.getFileName().toString(), () -> {
			System.out.println(path);
			try (HdfFile hdfFile = new HdfFile(path.toFile())) {
				recurseGroup(hdfFile);
			}
		});
	}

	private void recurseGroup(Group group) {
		for (Node node : group) {
			if (node instanceof Group) {
				Group group2 = (Group) node;
				verifyGroup(group2);
				recurseGroup(group2);
			} else if (node instanceof Dataset) {
				verifyDataset((Dataset) node, group);
			}
		}
	}

	/**
	 * Verifies things that should be true about all datasets
	 * 
	 * @param dataset the dataset to be exercised
	 * @param group   its parent group
	 */
	private void verifyDataset(Dataset dataset, Group group) {
		assertThat(dataset.getName(), is(notNullValue()));
		assertThat(dataset.getPath(), is(group.getPath() + dataset.getName()));
		assertThat(dataset.getParent(), is(sameInstance(group)));
		int[] dims = dataset.getDimensions();
		assertThat(dims, is(notNullValue()));
		Object data = dataset.getData();
		assertThat(getDimensions(data), is(equalTo(dims)));
		assertThat(getType(data), is(equalTo(dataset.getJavaType())));
		assertThat(dataset.getAttributes(), is(notNullValue()));
		assertThat(dataset.isGroup(), is(false));
		assertThat(dataset.isLink(), is(false));
		assertThat(dataset.getType(), is(NodeType.DATASET));
	}

	private int[] getDimensions(Object data) {
		List<Integer> dims = new ArrayList<>();
		dims.add(Array.getLength(data));

		while (Array.get(data, 0).getClass().isArray()) {
			data = Array.get(data, 0);
			dims.add(Array.getLength(data));
		}
		return ArrayUtils.toPrimitive(dims.toArray(new Integer[dims.size()]));
	}

	private Class<?> getType(Object data) {
		if (Array.get(data, 0).getClass().isArray()) {
			return getType(Array.get(data, 0));
		} else {
			return data.getClass().getComponentType();
		}
	}

	/**
	 * Verifies things that should be true about all groups
	 * 
	 * @param group to exercise
	 */
	private void verifyGroup(Group group) {
		assertThat(group.getAttributes(), is(notNullValue()));
		assertThat(group.isGroup(), is(true));
		assertThat(group.isLink(), is(false));
		assertThat(group.getType(), is(NodeType.GROUP));
	}
}