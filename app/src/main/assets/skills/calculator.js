/**
 * Safe Mathematical Expression Evaluator Skill.
 * Evaluates basic arithmetic without invoking `eval` or `Function()`.
 */
window.__edgeRun = function(input, networkOrigins) {
    try {
        const expression = (typeof input === 'object' && input.expression) ? input.expression : String(input);
        const result = safeCalculate(expression);
        window.__edgeHost.complete(JSON.stringify({
            status: "success",
            expression: expression,
            result: result
        }));
    } catch (err) {
        window.__edgeHost.fail("Calculator error: " + err.message);
    }
};

function safeCalculate(expr) {
    const tokens = tokenize(expr);
    return parseExpression(tokens);
}

function tokenize(str) {
    const regex = /\s*([0-9]+(?:\.[0-9]+)?|[+\-*/^()]|\b(?:sqrt|abs|sin|cos|tan)\b)\s*/g;
    const tokens = [];
    let match;
    let lastIndex = 0;
    while ((match = regex.exec(str)) !== null) {
        if (match.index !== lastIndex) {
            throw new Error("Invalid character at index " + lastIndex);
        }
        tokens.push(match[1]);
        lastIndex = regex.lastIndex;
    }
    if (lastIndex !== str.length && str.trim().length > 0) {
        throw new Error("Unrecognized token near end of expression");
    }
    return tokens;
}

function parseExpression(tokens) {
    let index = 0;

    function parsePrimary() {
        const token = tokens[index++];
        if (!token) throw new Error("Unexpected end of input");
        if (token === '(') {
            const val = parseAddSub();
            if (tokens[index++] !== ')') throw new Error("Missing closing parenthesis");
            return val;
        }
        if (token === 'sqrt') {
            const val = parsePrimary();
            return Math.sqrt(val);
        }
        if (token === 'abs') {
            const val = parsePrimary();
            return Math.abs(val);
        }
        const num = parseFloat(token);
        if (isNaN(num)) throw new Error("Expected number, got: " + token);
        return num;
    }

    function parseFactor() {
        let left = parsePrimary();
        while (tokens[index] === '^') {
            index++;
            const right = parsePrimary();
            left = Math.pow(left, right);
        }
        return left;
    }

    function parseMulDiv() {
        let left = parseFactor();
        while (tokens[index] === '*' || tokens[index] === '/') {
            const op = tokens[index++];
            const right = parseFactor();
            if (op === '*') left *= right;
            else {
                if (right === 0) throw new Error("Division by zero");
                left /= right;
            }
        }
        return left;
    }

    function parseAddSub() {
        let left = parseMulDiv();
        while (tokens[index] === '+' || tokens[index] === '-') {
            const op = tokens[index++];
            const right = parseMulDiv();
            if (op === '+') left += right;
            else left -= right;
        }
        return left;
    }

    const result = parseAddSub();
    if (index < tokens.length) {
        throw new Error("Unexpected token remaining: " + tokens[index]);
    }
    return result;
}
