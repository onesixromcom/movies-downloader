function decodeField(input) {
    // strip trailing ==
    const stripped = input.slice(0, -2);
    
    const decoded = atob(stripped);
    if (decoded.length < 1) return input;

    const key = decoded.charCodeAt(0);  // seed = first byte

    let result = '';
    for (let i = 1; i < decoded.length; i++) {
        const keystreamByte = (key + 7 * (i - 1) + 13) % 256;
        result += String.fromCharCode(decoded.charCodeAt(i) ^ keystreamByte);
    }

    try {
        return decodeURIComponent(escape(result));
    } catch {
        return result;
    }
}

const file = process.argv[2];

console.log(decodeField(file));
