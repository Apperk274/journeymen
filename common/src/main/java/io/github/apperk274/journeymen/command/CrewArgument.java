package io.github.apperk274.journeymen.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.List;

public final class CrewArgument implements ArgumentType<String> {

    private static final Collection<String> EXAMPLES = List.of("Bob", "Bob,Ann", "Steve.Bob,Steve.Ann");
    private static final SimpleCommandExceptionType EMPTY = new SimpleCommandExceptionType(Component.literal("Expected a builder name"));

    public static CrewArgument crew() {
        return new CrewArgument();
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        if (reader.canRead() && StringReader.isQuotedStringStart(reader.peek())) {
            return reader.readQuotedString();
        }
        int start = reader.getCursor();
        while (reader.canRead() && reader.peek() != ' ') {
            reader.skip();
        }
        if (reader.getCursor() == start) {
            throw EMPTY.createWithContext(reader);
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}
