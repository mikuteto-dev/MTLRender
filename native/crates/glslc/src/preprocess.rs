use crate::{CompileError, CompileErrors, Define, ErrorKind, Location, Options, Source};
use std::collections::BTreeMap;

#[derive(Debug, Eq, PartialEq)]
pub struct LineMapEntry {
    pub output_line: u32,
    pub source_index: u32,
    pub source_line: u32,
    pub source_path: String,
}

#[derive(Debug, Eq, PartialEq)]
pub struct LineMap {
    entries: Vec<LineMapEntry>,
}

impl LineMap {
    pub fn entries(&self) -> &[LineMapEntry] {
        &self.entries
    }

    pub fn source_line(&self, output_line: u32) -> Option<(u32, u32)> {
        self.entries
            .iter()
            .find(|entry| entry.output_line == output_line)
            .map(|entry| (entry.source_index, entry.source_line))
    }

    pub fn source_path(&self, output_line: u32) -> Option<&str> {
        self.entries
            .iter()
            .find(|entry| entry.output_line == output_line)
            .map(|entry| entry.source_path.as_str())
    }
}

#[derive(Debug, Eq, PartialEq)]
pub struct Version {
    pub number: u32,
    pub profile: String,
}

#[derive(Debug, Eq, PartialEq)]
pub struct Extension {
    pub name: String,
    pub behavior: String,
}

#[derive(Debug, Eq, PartialEq)]
pub struct PreprocessedSource {
    pub text: String,
    pub line_map: LineMap,
    pub version: Option<Version>,
    pub extensions: Vec<Extension>,
    pub pragmas: Vec<String>,
}

struct Macro {
    parameters: Option<Vec<String>>,
    replacement: String,
}

struct Conditional {
    parent: bool,
    active: bool,
    taken: bool,
    else_seen: bool,
}

fn error(path: &str, line: u32, message: impl Into<String>) -> CompileErrors {
    CompileErrors::one(CompileError::new(
        crate::ErrorKind::Preprocess,
        message,
        Some(Location::new(path, line, 1)),
    ))
}

fn words(text: &str) -> Vec<String> {
    let mut result = Vec::new();
    let mut current = String::new();
    let mut chars = text.chars().peekable();
    while let Some(ch) = chars.next() {
        if ch.is_ascii_whitespace() {
            if !current.is_empty() {
                result.push(std::mem::take(&mut current));
            }
        } else if ch.is_ascii_alphanumeric() || ch == '_' || ch == '.' {
            current.push(ch);
        } else {
            if !current.is_empty() {
                result.push(std::mem::take(&mut current));
            }
            let mut punctuation = String::new();
            punctuation.push(ch);
            if let Some(next) = chars.peek() {
                let pair = matches!(
                    (ch, *next),
                    ('&', '&')
                        | ('|', '|')
                        | ('=', '=')
                        | ('!', '=')
                        | ('<', '=')
                        | ('>', '=')
                        | ('<', '<')
                        | ('>', '>')
                        | ('#', '#')
                );
                if pair {
                    punctuation.push(*next);
                    let _ = chars.next();
                }
            }
            result.push(punctuation);
        }
    }
    if !current.is_empty() {
        result.push(current);
    }
    result
}

fn join(tokens: &[String]) -> String {
    let mut result = String::new();
    for token in tokens {
        if !result.is_empty() && needs_space(&result, token) {
            result.push(' ');
        }
        result.push_str(token);
    }
    result
}

fn needs_space(previous: &str, next: &str) -> bool {
    let a = previous
        .chars()
        .last()
        .is_some_and(|ch| ch.is_ascii_alphanumeric() || ch == '_');
    let b = next
        .chars()
        .next()
        .is_some_and(|ch| ch.is_ascii_alphanumeric() || ch == '_');
    a && b
}

fn stringify(tokens: &[String]) -> String {
    let mut result = String::from("\"");
    let body = join(tokens);
    for ch in body.chars() {
        if ch == '\\' || ch == '"' {
            result.push('\\');
        }
        result.push(ch);
    }
    result.push('"');
    result
}

fn parse_define(rest: &str) -> Option<(String, Macro)> {
    let mut chars = rest.chars().peekable();
    let mut name = String::new();
    while let Some(ch) = chars.peek() {
        if ch.is_ascii_alphanumeric() || *ch == '_' {
            name.push(*ch);
            let _ = chars.next();
        } else {
            break;
        }
    }
    if name.is_empty() {
        return None;
    }
    let mut parameters = None;
    if chars.peek() == Some(&'(') {
        let _ = chars.next();
        let mut list = Vec::new();
        let mut item = String::new();
        for ch in chars.by_ref() {
            if ch == ')' {
                if !item.trim().is_empty() {
                    list.push(item.trim().to_string());
                }
                break;
            }
            if ch == ',' {
                list.push(item.trim().to_string());
                item.clear();
            } else {
                item.push(ch);
            }
        }
        parameters = Some(list);
    }
    let replacement = chars.collect::<String>().trim().to_string();
    Some((
        name,
        Macro {
            parameters,
            replacement,
        },
    ))
}

struct Expansion<'a> {
    options: &'a Options,
    active: Vec<String>,
    size: u64,
}

fn expand(
    text: &str,
    macros: &BTreeMap<String, Macro>,
    line: u32,
    file: &str,
    depth: u32,
    state: &mut Expansion<'_>,
) -> Result<String, CompileErrors> {
    if depth > state.options.max_macro_expansion_depth {
        return Err(CompileErrors::one(CompileError::new(
            ErrorKind::Preprocess,
            "macro expansion depth limit exceeded",
            Some(Location::new(file, line, 1)),
        )));
    }
    let input = words(text);
    let mut output = Vec::new();
    let mut position = 0_usize;
    while let Some(token) = input.get(position) {
        if token == "__LINE__" {
            output.push(line.to_string());
            position += 1;
            continue;
        }
        if token == "__FILE__" {
            output.push(format!("\"{}\"", file));
            position += 1;
            continue;
        }
        let Some(mac) = macros.get(token) else {
            output.push(String::from(token.as_str()));
            position += 1;
            continue;
        };
        if state.active.iter().any(|value| value == token) {
            return Err(CompileErrors::one(CompileError::new(
                ErrorKind::Preprocess,
                format!("recursive macro expansion for {token}"),
                Some(Location::new(file, line, 1)),
            )));
        }
        if mac.parameters.is_none() {
            state.active.push(String::from(token.as_str()));
            let replacement = expand(
                &mac.replacement,
                macros,
                line,
                file,
                depth.saturating_add(1),
                state,
            )?;
            let _ = state.active.pop();
            output.extend(words(&replacement));
            position += 1;
            continue;
        }
        let Some(open) = input.get(position + 1) else {
            output.push(String::from(token.as_str()));
            position += 1;
            continue;
        };
        if open != "(" {
            output.push(String::from(token.as_str()));
            position += 1;
            continue;
        }
        let mut args: Vec<Vec<String>> = Vec::new();
        let mut current = Vec::new();
        let mut level = 0_u32;
        let mut cursor = position + 2;
        let mut closed = false;
        while let Some(part) = input.get(cursor) {
            if part == "(" {
                level += 1;
                current.push(String::from(part.as_str()));
            } else if part == ")" {
                if level == 0 {
                    args.push(current);
                    closed = true;
                    break;
                }
                level -= 1;
                current.push(String::from(part.as_str()));
            } else if part == "," && level == 0 {
                args.push(current);
                current = Vec::new();
            } else {
                current.push(String::from(part.as_str()));
            }
            cursor += 1;
        }
        if !closed {
            output.push(String::from(token.as_str()));
            position += 1;
            continue;
        }
        let Some(parameters) = mac.parameters.as_ref() else {
            output.push(String::from(token.as_str()));
            position += 1;
            continue;
        };
        if parameters.len() != args.len() && !(parameters.len() == 1 && args.is_empty()) {
            output.push(String::from(token.as_str()));
            position += 1;
            continue;
        }
        let mut replacement = words(&mac.replacement);
        let mut index = 0_usize;
        while let Some(part) = replacement.get(index) {
            if part == "#"
                && let Some(name) = replacement.get(index + 1)
                && let Some(parameter) = parameters.iter().position(|value| value == name)
            {
                let value = args
                    .get(parameter)
                    .map_or_else(String::new, |value| stringify(value));
                replacement.splice(index..=index + 1, [value]);
            }
            index += 1;
        }
        let mut substituted = Vec::new();
        for part in replacement {
            if let Some(parameter) = parameters.iter().position(|value| value == &part) {
                let value = args.get(parameter).map_or_else(Vec::new, |value| {
                    value
                        .iter()
                        .map(|item| String::from(item.as_str()))
                        .collect()
                });
                substituted.extend(value);
            } else {
                substituted.push(part);
            }
        }
        let mut pasted = Vec::new();
        let mut index = 0_usize;
        while let Some(part) = substituted.get(index) {
            if part == "##"
                && let (Some(left), Some(right)) = (pasted.pop(), substituted.get(index + 1))
            {
                pasted.push(format!("{}{}", left, right));
                index += 2;
                continue;
            }
            pasted.push(String::from(part.as_str()));
            index += 1;
        }
        state.active.push(String::from(token.as_str()));
        let expanded = expand(
            &join(&pasted),
            macros,
            line,
            file,
            depth.saturating_add(1),
            state,
        )?;
        let _ = state.active.pop();
        output.extend(words(&expanded));
        position = cursor + 1;
    }
    let result = join(&output);
    let result_bytes = u64::try_from(result.len()).map_err(|_| {
        CompileErrors::one(CompileError::new(
            ErrorKind::Preprocess,
            "expanded macro size cannot be represented",
            Some(Location::new(file, line, 1)),
        ))
    })?;
    state.size = state.size.saturating_add(result_bytes);
    if state.size > state.options.max_macro_expanded_bytes {
        return Err(CompileErrors::one(CompileError::new(
            ErrorKind::Preprocess,
            "macro expansion size limit exceeded",
            Some(Location::new(file, line, 1)),
        )));
    }
    Ok(result)
}

struct Expr<'a> {
    tokens: Vec<String>,
    position: usize,
    macros: &'a BTreeMap<String, Macro>,
}

impl<'a> Expr<'a> {
    fn peek(&self) -> Option<&str> {
        self.tokens.get(self.position).map(String::as_str)
    }
    fn take(&mut self) -> Option<String> {
        let value = self
            .tokens
            .get(self.position)
            .map(|value| String::from(value.as_str()));
        self.position += 1;
        value
    }
    fn primary(&mut self) -> i64 {
        if self.peek() == Some("(") {
            let _ = self.take();
            let value = self.or();
            let _ = self.take();
            return value;
        }
        if self.peek() == Some("defined") {
            let _ = self.take();
            let parenthesized = self.peek() == Some("(");
            if parenthesized {
                let _ = self.take();
            }
            let name = self.take().unwrap_or_default();
            if parenthesized {
                let _ = self.take();
            }
            return i64::from(self.macros.contains_key(&name));
        }
        self.take()
            .and_then(|value| value.parse::<i64>().ok())
            .unwrap_or(0)
    }
    fn unary(&mut self) -> i64 {
        match self.peek() {
            Some("!") => {
                let _ = self.take();
                i64::from(self.unary() == 0)
            }
            Some("-") => {
                let _ = self.take();
                -self.unary()
            }
            Some("+") => {
                let _ = self.take();
                self.unary()
            }
            _ => self.primary(),
        }
    }
    fn multiply(&mut self) -> i64 {
        let mut value = self.unary();
        loop {
            match self.peek() {
                Some("*") => {
                    let _ = self.take();
                    value = value.saturating_mul(self.unary());
                }
                Some("/") => {
                    let _ = self.take();
                    let rhs = self.unary();
                    if rhs != 0 {
                        value /= rhs;
                    } else {
                        value = 0;
                    }
                }
                Some("%") => {
                    let _ = self.take();
                    let rhs = self.unary();
                    if rhs != 0 {
                        value %= rhs;
                    } else {
                        value = 0;
                    }
                }
                _ => return value,
            }
        }
    }
    fn add(&mut self) -> i64 {
        let mut value = self.multiply();
        loop {
            match self.peek() {
                Some("+") => {
                    let _ = self.take();
                    value = value.saturating_add(self.multiply());
                }
                Some("-") => {
                    let _ = self.take();
                    value = value.saturating_sub(self.multiply());
                }
                _ => return value,
            }
        }
    }
    fn shift(&mut self) -> i64 {
        let mut value = self.add();
        loop {
            match self.peek() {
                Some("<<") => {
                    let _ = self.take();
                    let rhs = self.add();
                    value = if (0..64).contains(&rhs) {
                        value.wrapping_shl(rhs as u32)
                    } else {
                        0
                    };
                }
                Some(">>") => {
                    let _ = self.take();
                    let rhs = self.add();
                    value = if (0..64).contains(&rhs) {
                        value.wrapping_shr(rhs as u32)
                    } else {
                        0
                    };
                }
                _ => return value,
            }
        }
    }
    fn compare(&mut self) -> i64 {
        let mut value = self.shift();
        loop {
            let op = self.peek().map(str::to_string);
            match op.as_deref() {
                Some("<") => {
                    let _ = self.take();
                    value = i64::from(value < self.shift());
                }
                Some("<=") => {
                    let _ = self.take();
                    value = i64::from(value <= self.shift());
                }
                Some(">") => {
                    let _ = self.take();
                    value = i64::from(value > self.shift());
                }
                Some(">=") => {
                    let _ = self.take();
                    value = i64::from(value >= self.shift());
                }
                _ => return value,
            }
        }
    }
    fn equality(&mut self) -> i64 {
        let mut value = self.compare();
        loop {
            match self.peek() {
                Some("==") => {
                    let _ = self.take();
                    value = i64::from(value == self.compare());
                }
                Some("!=") => {
                    let _ = self.take();
                    value = i64::from(value != self.compare());
                }
                _ => return value,
            }
        }
    }
    fn bit_and(&mut self) -> i64 {
        let mut value = self.equality();
        while self.peek() == Some("&") {
            let _ = self.take();
            value &= self.equality();
        }
        value
    }
    fn bit_xor(&mut self) -> i64 {
        let mut value = self.bit_and();
        while self.peek() == Some("^") {
            let _ = self.take();
            value ^= self.bit_and();
        }
        value
    }
    fn bit_or(&mut self) -> i64 {
        let mut value = self.bit_xor();
        while self.peek() == Some("|") {
            let _ = self.take();
            value |= self.bit_xor();
        }
        value
    }
    fn and(&mut self) -> i64 {
        let mut value = self.bit_or();
        while self.peek() == Some("&&") {
            let _ = self.take();
            value = i64::from(value != 0 && self.bit_or() != 0);
        }
        value
    }
    fn or(&mut self) -> i64 {
        let mut value = self.and();
        while self.peek() == Some("||") {
            let _ = self.take();
            value = i64::from(value != 0 || self.and() != 0);
        }
        value
    }
}

fn condition(
    text: &str,
    macros: &BTreeMap<String, Macro>,
    line: u32,
    file: &str,
    state: &mut Expansion<'_>,
) -> Result<bool, CompileErrors> {
    let mut prepared = String::new();
    let tokens = words(text);
    let mut position = 0_usize;
    while let Some(token) = tokens.get(position) {
        if token == "defined" {
            let parenthesized = tokens.get(position + 1).is_some_and(|value| value == "(");
            let name_position = if parenthesized {
                position + 2
            } else {
                position + 1
            };
            let name = tokens.get(name_position).map(String::as_str).unwrap_or("");
            prepared.push_str(if macros.contains_key(name) { "1" } else { "0" });
            position = if parenthesized {
                name_position.saturating_add(2)
            } else {
                name_position.saturating_add(1)
            };
        } else {
            if !prepared.is_empty() {
                prepared.push(' ');
            }
            prepared.push_str(token);
            position += 1;
        }
    }
    let expanded = expand(&prepared, macros, line, file, 0, state)?;
    let mut expression = words(&expanded);
    for token in &mut expression {
        if !token.chars().all(|ch| ch.is_ascii_digit() || ch == '-') && token != "defined" {
            *token = String::from("0");
        }
    }
    Ok(Expr {
        tokens: expression,
        position: 0,
        macros,
    }
    .or()
        != 0)
}

pub fn run(
    sources: &[Source],
    defines: &[Define],
    options: &Options,
) -> Result<PreprocessedSource, CompileErrors> {
    let source = sources.first().ok_or_else(|| {
        CompileErrors::one(CompileError::input(
            "at least one GLSL source file is required",
        ))
    })?;
    let mut macros = BTreeMap::new();
    for define in defines {
        macros.insert(
            define.name().to_string(),
            Macro {
                parameters: None,
                replacement: define.value().unwrap_or("1").to_string(),
            },
        );
    }
    let mut output = String::new();
    let mut entries = Vec::new();
    let mut conditionals = Vec::new();
    let mut version = None;
    let mut extensions = Vec::new();
    let mut pragmas = Vec::new();
    let mut expansion = Expansion {
        options,
        active: Vec::new(),
        size: 0,
    };
    let mut logical_line = 1_u32;
    let mut logical_file = source.path().to_string();
    for (number, raw) in source.text().split('\n').enumerate() {
        let source_line = match u32::try_from(number + 1) {
            Ok(value) => value,
            Err(_) => return Err(error(source.path(), 1, "source contains too many lines")),
        };
        let trimmed = raw.trim_start();
        let active = conditionals.iter().all(|state: &Conditional| state.active);
        if let Some(rest) = trimmed.strip_prefix('#') {
            let mut parts = rest.trim_start().splitn(2, char::is_whitespace);
            let directive = parts.next().unwrap_or("");
            let argument = parts.next().unwrap_or("").trim();
            match directive {
                "if" => {
                    let parent = active;
                    let value = condition(
                        argument,
                        &macros,
                        logical_line,
                        &logical_file,
                        &mut expansion,
                    )?;
                    conditionals.push(Conditional {
                        parent,
                        active: parent && value,
                        taken: parent && value,
                        else_seen: false,
                    });
                }
                "ifdef" | "ifndef" => {
                    let parent = active;
                    let present = macros.contains_key(argument);
                    let value = if directive == "ifdef" {
                        present
                    } else {
                        !present
                    };
                    conditionals.push(Conditional {
                        parent,
                        active: parent && value,
                        taken: parent && value,
                        else_seen: false,
                    });
                }
                "elif" => {
                    if let Some(state) = conditionals.last_mut() {
                        if state.else_seen {
                            return Err(error(source.path(), source_line, "#elif after #else"));
                        }
                        let value = state.parent
                            && !state.taken
                            && condition(
                                argument,
                                &macros,
                                logical_line,
                                &logical_file,
                                &mut expansion,
                            )?;
                        state.active = value;
                        state.taken |= value;
                    } else {
                        return Err(error(source.path(), source_line, "#elif without #if"));
                    }
                }
                "else" => {
                    if let Some(state) = conditionals.last_mut() {
                        if state.else_seen {
                            return Err(error(source.path(), source_line, "duplicate #else"));
                        }
                        state.else_seen = true;
                        state.active = state.parent && !state.taken;
                        state.taken = true;
                    } else {
                        return Err(error(source.path(), source_line, "#else without #if"));
                    }
                }
                "endif" => {
                    if conditionals.pop().is_none() {
                        return Err(error(source.path(), source_line, "#endif without #if"));
                    }
                }
                "define" if active => {
                    if let Some((name, mac)) = parse_define(argument) {
                        macros.insert(name, mac);
                    } else {
                        return Err(error(
                            source.path(),
                            source_line,
                            "invalid macro definition",
                        ));
                    }
                }
                "undef" if active => {
                    let name = argument.split_whitespace().next().unwrap_or("");
                    macros.remove(name);
                }
                "error" if active => {
                    return Err(error(
                        source.path(),
                        source_line,
                        if argument.is_empty() {
                            "#error"
                        } else {
                            argument
                        },
                    ));
                }
                "version" if active => {
                    let mut fields = argument.split_whitespace();
                    let number = fields.next().and_then(|value| value.parse::<u32>().ok());
                    if version.is_some() || number.is_none() {
                        return Err(error(
                            source.path(),
                            source_line,
                            "invalid or repeated #version",
                        ));
                    }
                    version = number.map(|number| Version {
                        number,
                        profile: fields.next().unwrap_or("core").to_string(),
                    });
                }
                "extension" if active => {
                    let mut fields = argument.splitn(2, ':');
                    let name = fields.next().unwrap_or("").trim();
                    let behavior = fields.next().unwrap_or("").trim();
                    if !name.is_empty() && !behavior.is_empty() {
                        extensions.push(Extension {
                            name: name.to_string(),
                            behavior: behavior.to_string(),
                        });
                    } else {
                        return Err(error(source.path(), source_line, "invalid #extension"));
                    }
                }
                "pragma" if active => pragmas.push(argument.to_string()),
                "line" if active => {
                    let mut fields = argument.split_whitespace();
                    if let Some(value) = fields.next().and_then(|value| value.parse::<u32>().ok()) {
                        logical_line = value.saturating_sub(1);
                        if let Some(file) = fields.next() {
                            logical_file = file.trim_matches('"').to_string();
                        }
                    } else {
                        return Err(error(source.path(), source_line, "invalid #line"));
                    }
                }
                _ => {}
            }
            if matches!(directive, "version" | "extension" | "pragma" | "line") {
                output.push_str(raw);
            }
            output.push('\n');
        } else if active {
            let expanded = expand(raw, &macros, logical_line, &logical_file, 0, &mut expansion)?;
            output.push_str(&expanded);
            output.push('\n');
        } else {
            output.push('\n');
        }
        entries.push(LineMapEntry {
            output_line: source_line,
            source_index: 0,
            source_line: logical_line,
            source_path: String::from(logical_file.as_str()),
        });
        logical_line = logical_line.saturating_add(1);
    }
    if !conditionals.is_empty() {
        return Err(error(
            source.path(),
            source_line_count(source.text()),
            "unterminated conditional directive",
        ));
    }
    Ok(PreprocessedSource {
        text: output,
        line_map: LineMap { entries },
        version,
        extensions,
        pragmas,
    })
}

fn source_line_count(text: &str) -> u32 {
    u32::try_from(text.split('\n').count()).unwrap_or(u32::MAX)
}

#[cfg(test)]
mod tests {
    use super::run;
    use crate::{Define, Options, Source};

    fn process(text: &str) -> Option<super::PreprocessedSource> {
        run(
            &[Source::new("shader.glsl", text)],
            &[],
            &Options::default(),
        )
        .ok()
    }

    #[test]
    fn expands_function_macros_stringification_and_pasting() {
        let Some(result) =
            process("#define CAT(a,b) a##b\n#define NAME(x) #x\nCAT(fo,o) NAME(hello world)\n")
        else {
            return;
        };
        assert!(result.text.contains("foo"));
        assert!(result.text.contains("\"hello world\""));
    }

    #[test]
    fn evaluates_defined_and_constant_expression_operators() {
        let Some(result) = process(
            "#define FLAG 1\n#if defined(FLAG) && ((3 << 2) | 1) == 13\nok\n#else\nbad\n#endif\n",
        ) else {
            return;
        };
        assert!(result.text.contains("ok"));
        assert!(!result.text.contains("bad"));
    }

    #[test]
    fn records_directives_and_preserves_line_map() {
        let Some(result) = process(
            "#version 460 core\n#extension GL_ARB_separate_shader_objects : enable\n#pragma optimize(on)\n#line 20 \"logical.glsl\"\n__LINE__ __FILE__\n",
        ) else {
            return;
        };
        assert_eq!(result.version.as_ref().map(|value| value.number), Some(460));
        assert_eq!(
            result.version.as_ref().map(|value| value.profile.as_str()),
            Some("core")
        );
        assert_eq!(result.extensions.len(), 1);
        assert_eq!(result.pragmas, vec!["optimize(on)"]);
        assert!(result.text.contains("20\"logical.glsl\""));
        assert_eq!(result.line_map.source_line(5), Some((0, 20)));
        assert_eq!(result.line_map.source_path(5), Some("logical.glsl"));
    }

    #[test]
    fn accepts_lenient_unknown_directives_and_redefinitions() {
        let Some(result) =
            process("#define VALUE 1\n#define VALUE 2\n#unknown trailing tokens\nVALUE\n")
        else {
            return;
        };
        assert!(result.text.contains("2"));
    }

    #[test]
    fn command_line_defines_are_available() {
        let Some(result) = run(
            &[Source::new("shader.glsl", "#if FLAG == 7\nyes\n#endif\n")],
            &[Define::new("FLAG", Some("7"))],
            &Options::default(),
        )
        .ok() else {
            return;
        };
        assert!(result.text.contains("yes"));
    }
}
