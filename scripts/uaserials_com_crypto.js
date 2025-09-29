var CryptoJS = require("crypto-js");

var passphrase = '297796CCB81D2551';

CryptoJSAesDecrypt = function (passphrase, encrypted_json_string){
	let obj_json = JSON.parse(encrypted_json_string);

	let encrypted = obj_json.ciphertext;
	let salt = CryptoJS.enc.Hex.parse(obj_json.salt);
	let iv = CryptoJS.enc.Hex.parse(obj_json.iv);   

	let key = CryptoJS.PBKDF2(passphrase, salt, { hasher: CryptoJS.algo.SHA512, keySize: 64/8, iterations: 999});

	let decrypted = CryptoJS.AES.decrypt(encrypted, key, { iv: iv});

	let result_string = decrypted.toString(CryptoJS.enc.Utf8);
	return JSON.parse(result_string.replace(/(\r\n|\n|\r)/gm, "").replace(/\\/gm, ""));
}

var encrypted_json_string = process.argv[2];
var season_num = process.argv[3] ? process.argv[3] : 0;
var voice_num = process.argv[4] ? process.argv[4] : 0;

var result = CryptoJSAesDecrypt(passphrase, encrypted_json_string);

var urls = [];
for (item of result) {

	if (item.tabName == 'Плеєр') {
		if (typeof item.url !== 'undefined') {
			urls.push(item.url);
		}
		if (typeof item.seasons !== 'undefined') {
			// Fallback to first season.
			if (season_num > 0 && typeof item.seasons[season_num] == 'undefined' && item.seasons[0]) {
				season_num = 0;
			}
			if (item.seasons[season_num]) {
				for (episode of item.seasons[season_num].episodes){
					if (typeof episode.sounds[voice_num] !== 'undefined') {
						urls.push(episode.sounds[voice_num].url);
					}
					// Fallback to the first voice.
					else if (typeof episode.sounds[0] !== 'undefined') {
						urls.push(episode.sounds[0].url);
					}
				}
			}
		}
	}
}

console.log(urls.join(','));
