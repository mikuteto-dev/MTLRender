use crate::{CompileError, CompileErrors, ErrorKind, Location, preprocess::PreprocessedSource};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TokenKind {
    Identifier,
    Integer,
    Float,
    String,
    Punctuation,
    Operator,
    Directive,
    EndOfFile,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Span {
    pub start: u32,
    pub end: u32,
    pub line: u32,
    pub column: u32,
    pub source_index: u32,
    pub source_path: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Token {
    pub kind: TokenKind,
    pub text: String,
    pub span: Span,
}

#[derive(Debug, Eq, PartialEq)]
pub struct TokenStream {
    pub tokens: Vec<Token>,
}

const OPERATORS: &[&str] = &[
    "<<<", ">>>", "<<=", ">>=", "==", "!=", "<=", ">=", "&&", "||", "^^", "++", "--", "+=", "-=",
    "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>", "::", "->",
];

fn is_identifier_start(value: u8) -> bool {
    value == b'_' || value.is_ascii_alphabetic()
}

fn is_identifier_continue(value: u8) -> bool {
    value == b'_' || value.is_ascii_alphanumeric()
}

fn segment(source: &PreprocessedSource, start: usize, end: usize) -> &str {
    source.text.get(start..end).map_or("", |value| value)
}

fn location(source: &PreprocessedSource, line: u32, column: u32) -> Option<Location> {
    let (_, source_line) = source.line_map.source_line(line)?;
    let source_path = source.line_map.source_path(line)?;
    Some(Location::new(source_path, source_line, column))
}

fn token(
    kind: TokenKind,
    text: &str,
    start: usize,
    end: usize,
    line: u32,
    column: u32,
    source_path: &str,
) -> Option<Token> {
    Some(Token {
        kind,
        text: String::from(text),
        span: Span {
            start: u32::try_from(start).ok()?,
            end: u32::try_from(end).ok()?,
            line,
            column,
            source_index: 0,
            source_path: String::from(source_path),
        },
    })
}

pub fn run(source: &PreprocessedSource) -> Result<TokenStream, CompileErrors> {
    let bytes = source.text.as_bytes();
    let mut tokens = Vec::new();
    let mut position = 0usize;
    let mut line = 1u32;
    let mut column = 1u32;
    let mut errors = Vec::new();
    while let Some(&current) = bytes.get(position) {
        if current == b' ' || current == b'\t' || current == b'\r' {
            position += 1;
            column += 1;
            continue;
        }
        if current == b'\n' {
            position += 1;
            line += 1;
            column = 1;
            continue;
        }
        if current == b'/' && bytes.get(position + 1) == Some(&b'/') {
            position += 2;
            column += 2;
            while let Some(&value) = bytes.get(position) {
                if value == b'\n' {
                    break;
                }
                position += 1;
                column += 1;
            }
            continue;
        }
        if current == b'/' && bytes.get(position + 1) == Some(&b'*') {
            position += 2;
            column += 2;
            let mut closed = false;
            while let Some(&value) = bytes.get(position) {
                if value == b'*' && bytes.get(position + 1) == Some(&b'/') {
                    position += 2;
                    column += 2;
                    closed = true;
                    break;
                }
                if value == b'\n' {
                    position += 1;
                    line += 1;
                    column = 1;
                } else {
                    position += 1;
                    column += 1;
                }
            }
            if !closed {
                errors.push(CompileError::new(
                    ErrorKind::Lex,
                    "unterminated block comment",
                    location(source, line, column),
                ));
            }
            continue;
        }
        let start = position;
        let start_line = line;
        let start_column = column;
        if current == b'#' {
            while let Some(&value) = bytes.get(position) {
                if value == b'\n' {
                    break;
                }
                position += 1;
                column += 1;
            }
            if let Some(value) = token(
                TokenKind::Directive,
                segment(source, start, position),
                start,
                position,
                start_line,
                start_column,
                source.line_map.source_path(start_line).unwrap_or(""),
            ) {
                tokens.push(value);
            }
            continue;
        }
        if is_identifier_start(current) {
            position += 1;
            column += 1;
            while let Some(&value) = bytes.get(position) {
                if !is_identifier_continue(value) {
                    break;
                }
                position += 1;
                column += 1;
            }
            if let Some(value) = token(
                TokenKind::Identifier,
                segment(source, start, position),
                start,
                position,
                start_line,
                start_column,
                source.line_map.source_path(start_line).unwrap_or(""),
            ) {
                tokens.push(value);
            }
            continue;
        }
        if current.is_ascii_digit()
            || (current == b'.' && bytes.get(position + 1).is_some_and(u8::is_ascii_digit))
        {
            let mut cursor = position;
            let mut has_dot = false;
            let hexadecimal = bytes.get(cursor) == Some(&b'0')
                && matches!(bytes.get(cursor + 1), Some(b'x' | b'X'));
            if hexadecimal {
                cursor += 2;
                while bytes
                    .get(cursor)
                    .is_some_and(|value| value.is_ascii_hexdigit())
                {
                    cursor += 1;
                }
                if bytes.get(cursor) == Some(&b'.') {
                    has_dot = true;
                    cursor += 1;
                    while bytes
                        .get(cursor)
                        .is_some_and(|value| value.is_ascii_hexdigit())
                    {
                        cursor += 1;
                    }
                }
                if matches!(bytes.get(cursor), Some(b'p' | b'P')) {
                    has_dot = true;
                    cursor += 1;
                    if matches!(bytes.get(cursor), Some(b'+' | b'-')) {
                        cursor += 1;
                    }
                    while bytes.get(cursor).is_some_and(u8::is_ascii_digit) {
                        cursor += 1;
                    }
                }
            } else {
                if current == b'.' {
                    has_dot = true;
                    cursor += 1;
                }
                while bytes.get(cursor).is_some_and(u8::is_ascii_digit) {
                    cursor += 1;
                }
                if bytes.get(cursor) == Some(&b'.') {
                    has_dot = true;
                    cursor += 1;
                    while bytes.get(cursor).is_some_and(u8::is_ascii_digit) {
                        cursor += 1;
                    }
                }
                if matches!(bytes.get(cursor), Some(b'e' | b'E')) {
                    has_dot = true;
                    cursor += 1;
                    if matches!(bytes.get(cursor), Some(b'+' | b'-')) {
                        cursor += 1;
                    }
                    while bytes.get(cursor).is_some_and(u8::is_ascii_digit) {
                        cursor += 1;
                    }
                }
            }
            while matches!(
                bytes.get(cursor),
                Some(b'f' | b'F' | b'u' | b'U' | b'l' | b'L')
            ) {
                has_dot |= matches!(bytes.get(cursor), Some(b'f' | b'F'));
                cursor += 1;
            }
            column += u32::try_from(cursor - position).unwrap_or(0);
            position = cursor;
            let kind = if has_dot {
                TokenKind::Float
            } else {
                TokenKind::Integer
            };
            if let Some(value) = token(
                kind,
                segment(source, start, position),
                start,
                position,
                start_line,
                start_column,
                source.line_map.source_path(start_line).unwrap_or(""),
            ) {
                tokens.push(value);
            }
            continue;
        }
        if current == b'"' || current == b'\'' {
            let quote = current;
            position += 1;
            column += 1;
            let mut closed = false;
            while let Some(&value) = bytes.get(position) {
                position += 1;
                column += 1;
                if value == b'\\' {
                    position += 1;
                    column += 1;
                    continue;
                }
                if value == quote {
                    closed = true;
                    break;
                }
                if value == b'\n' {
                    break;
                }
            }
            if !closed {
                errors.push(CompileError::new(
                    ErrorKind::Lex,
                    "unterminated literal",
                    location(source, start_line, start_column),
                ));
            }
            if let Some(value) = token(
                TokenKind::String,
                segment(source, start, position),
                start,
                position,
                start_line,
                start_column,
                source.line_map.source_path(start_line).unwrap_or(""),
            ) {
                tokens.push(value);
            }
            continue;
        }
        let operator = OPERATORS.iter().find(|candidate| {
            source
                .text
                .get(position..)
                .is_some_and(|value| value.starts_with(**candidate))
        });
        let (kind, length) = if let Some(value) = operator {
            (TokenKind::Operator, value.len())
        } else if b"{}()[];,.:?".contains(&current) {
            (TokenKind::Punctuation, 1)
        } else {
            (TokenKind::Operator, 1)
        };
        position += length;
        column += length as u32;
        if let Some(value) = token(
            kind,
            segment(source, start, position),
            start,
            position,
            start_line,
            start_column,
            source.line_map.source_path(start_line).unwrap_or(""),
        ) {
            tokens.push(value);
        }
    }
    if let Some(value) = token(
        TokenKind::EndOfFile,
        "",
        position,
        position,
        line,
        column,
        source.line_map.source_path(line).unwrap_or(""),
    ) {
        tokens.push(value);
    }
    if errors.is_empty() {
        Ok(TokenStream { tokens })
    } else {
        Err(CompileErrors::from_vec(errors))
    }
}

#[cfg(test)]
mod tests {
    use super::{TokenKind, run};
    use crate::{Options, Source, preprocess};

    #[test]
    fn lexes_literals_comments_directives_and_compound_operators() {
        let source = Source::new(
            "shader.fsh",
            "#version 460 core\n// x\nvoid main() { float x = 0x1.fp2; x >>= 1; }\n",
        );
        let preprocessed = preprocess::run(&[source], &[], &Options::default());
        let preprocessed = match preprocessed {
            Ok(value) => value,
            Err(_) => return,
        };
        let stream = run(&preprocessed);
        let stream = match stream {
            Ok(value) => value,
            Err(_) => return,
        };
        assert_eq!(
            stream.tokens.first().map(|token| token.kind),
            Some(TokenKind::Directive)
        );
        assert!(
            stream
                .tokens
                .iter()
                .any(|token| token.text == "0x1.fp2" && token.kind == TokenKind::Float)
        );
        assert!(
            stream
                .tokens
                .iter()
                .any(|token| token.text == ">>=" && token.kind == TokenKind::Operator)
        );
    }

    #[test]
    fn stops_numbers_before_adjacent_operators() {
        let source = Source::new(
            "number.glsl",
            "void main() { float x = 1.0-value; x = 0.5+value; }",
        );
        let preprocessed = match preprocess::run(&[source], &[], &Options::default()) {
            Ok(value) => value,
            Err(_) => return,
        };
        let stream = match run(&preprocessed) {
            Ok(value) => value,
            Err(_) => return,
        };
        assert!(stream.tokens.iter().any(|token| token.text == "1.0"));
        assert!(stream.tokens.iter().any(|token| token.text == "-"));
        assert!(stream.tokens.iter().any(|token| token.text == "0.5"));
        assert!(stream.tokens.iter().any(|token| token.text == "+"));
    }
}
