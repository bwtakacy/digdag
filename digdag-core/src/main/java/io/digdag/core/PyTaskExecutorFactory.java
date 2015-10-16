package io.digdag.core;

import java.util.List;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.io.OutputStream;
import java.io.Writer;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import com.google.common.base.*;
import com.google.common.collect.*;
import com.google.common.io.ByteStreams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;

public class PyTaskExecutorFactory
        implements TaskExecutorFactory
{
    private final ObjectMapper mapper;

    @Inject
    public PyTaskExecutorFactory(ObjectMapper mapper)
    {
        this.mapper = mapper;
    }

    public String getType()
    {
        return "py";
    }

    public TaskExecutor newTaskExecutor(ConfigSource config, ConfigSource params, ConfigSource state)
    {
        return new PyTaskExecutor(config, params, state);
    }

    private class PyTaskExecutor
            extends BaseTaskExecutor
    {
        public PyTaskExecutor(ConfigSource config, ConfigSource params, ConfigSource state)
        {
            super(config, params, state);
        }

        @Override
        public ConfigSource runTask(ConfigSource config, ConfigSource params)
        {
            ConfigSource data;
            try {
                data = runCode(config, params, "run");
            }
            catch (IOException | InterruptedException ex) {
                throw Throwables.propagate(ex);
            }

            subtaskConfig.setAll(data.getNestedOrGetEmpty("sub"));
            inputs.addAll(data.getListOrEmpty("inputs", ConfigSource.class));
            outputs.addAll(data.getListOrEmpty("outputs", ConfigSource.class));
            return data.getNestedOrGetEmpty("carry_params");
        }

        private ConfigSource runCode(ConfigSource config, ConfigSource params, String methodName)
                throws IOException, InterruptedException
        {
            File inFile = File.createTempFile("digdag-py-in-", ".tmp");  // TODO use TempFileAllocator
            File outFile = File.createTempFile("digdag-py-out-", ".tmp");  // TODO use TempFileAllocator

            if (config.has("command")) {
                String command = config.get("command", String.class);
                String[] fragments = command.split("\\.");
                String klass = fragments[fragments.length - 1];

                StringBuilder sb = new StringBuilder();
                if (fragments.length > 1) {
                    String pkg = Arrays.asList(fragments).subList(0, fragments.length-1)
                        .stream()
                        .collect(Collectors.joining(", "));
                    sb.append("from ")
                        .append(pkg)
                        .append(" import ")
                        .append(klass)
                        .append("\n");
                }
                sb.append("task = ").append(klass).append("(config, state, params)").append("\n");
                sb.append("task.").append(methodName).append("()\n\n");

                sb.append("out = dict()\n");
                sb.append("if hasattr(task, 'sub'):\n");
                sb.append("    out['sub'] = task.sub\n");
                sb.append("if hasattr(task, 'carry_params'):\n");
                sb.append("    out['carry_params'] = task.carry_params\n");
                sb.append("if hasattr(task, 'inputs'):\n");
                sb.append("    out['inputs'] = task.inputs  # TODO check callable\n");
                sb.append("if hasattr(task, 'outputs'):\n");
                sb.append("    out['outputs'] = task.outputs  # TODO check callable\n");
                sb.append("with open(out_file, 'w') as out_file:\n");
                sb.append("    json.dump(out, out_file)\n");

                config.set("script", sb.toString());
            }

            String script = config.get("script", String.class);

            StringBuilder sb = new StringBuilder();
            sb.append("import json\n");
            sb.append("in_file = \"").append(inFile.getPath()).append("\"\n");
            sb.append("out_file = \"").append(outFile.getPath()).append("\"\n");
            sb.append("with open(in_file) as f:\n");
            sb.append("    in_data = json.load(f)\n");
            sb.append("    config = in_data['config']\n");
            sb.append("    params = in_data['params']\n");
            sb.append("    state = in_data['state']\n");
            sb.append("\n");
            sb.append(script);

            String code = sb.toString();

            System.out.println("Python code: "+code);

            try (FileOutputStream fo = new FileOutputStream(inFile)) {
                mapper.writeValue(fo, ImmutableMap.of(
                            "config", config,
                            "params", params,
                            "state", state));
            }

            ProcessBuilder pb = new ProcessBuilder("python", "-");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (Writer writer = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()))) {
                writer.write(code);
            }
            try (InputStream stdout = p.getInputStream()) {
                ByteStreams.copy(stdout, System.out);
            }
            int ecode = p.waitFor();

            if (ecode != 0) {
                throw new RuntimeException("Python command failed");
            }

            return mapper.readValue(outFile, ConfigSource.class);
        }
    }
}