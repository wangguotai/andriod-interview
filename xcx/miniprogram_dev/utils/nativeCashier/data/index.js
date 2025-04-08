// region 基本字符串处理
/**
 * 检查一个字符串是否包含另一个子串
 * @param {string} source - 要搜索的源字符串
 * @param {string} substring - 要查找的子串
 * @returns {boolean} 如果source包含substring，则返回true；否则返回false
 */
// ======================基本字符串扩展==========================
function containsSubstring(source, substring) {
    // 空字符串或非字符串类型视为无效输入
    if (!isValidString(source) || !isValidString(substring)) {
        return false;
    }

    return source.includes(substring);
}

/**
 * 检查给定值是否是一个有效的字符串
 * @param {*} value - 待检查的值
 * @returns {boolean} 如果value是一个非空字符串，则返回true；否则返回false
 */
function isValidString(value) {
    return typeof value === 'string' && value.trim() !== '';
}

/**
 * 检查给定的字符串是否为空
 * @param {string} str - 要检查的字符串
 * @returns {boolean} 如果str为空，则返回true；否则返回false
 */
function isEmptyStr(str) {
    if (isTypeString(str)) {
        return str === 'undefined' || str === undefined || str === null || str === 'null' || str.trim().length === 0;
    } else {
        return true;
    }
}

/**
 * 检查给定的字符串是否为非空
 * @param {string} str - 要检查的字符串
 * @returns {boolean} 如果str为空，则返回true；否则返回false
 */
function isNonEmptyString(str) {
    return typeof str === 'string' && str.trim().length > 0;
}
/**
 * 检查给定的参数是否为字符串类型
 * @param {*} param - 待检查的参数
 * @returns {boolean} 如果param是字符串类型，则返回true；否则返回false
 */
function isTypeString(param) {
    return !!(param && (typeof param == 'string') && param.length);

}

/**
 * 将字符串转换为布尔值
 * @param {string | boolean} obj - 要转换的字符串
 * @returns {boolean} 转换后的布尔值
 */
function parseToBool(obj) {
    return obj === true || obj === 'true';
}

/**
 * 将字符串转换为整数
 * @param {string} str - 要转换的字符串
 * @param {number} def - 转换失败时的默认值
 * @returns {number} 转换后的整数
 */
function parseStrToInt(str, def = undefined) {
    try {
        return parseInt(str);
    } catch (e) {
        return def || -1;
    }
}

// endregion

// region 处理业务字符串
// ========================处理业务字符串==========================
/**
 *  去除字符串中重复元素（逗号分隔）并返回处理后的结果
 * @param {string} str - 要处理的字符串
 * @returns {string} 去重后的字符串
 */
function removeDuplicates(str) {
    // 定义一个空字符串，用于存储去重后的结果
    let resultString = '';
    // 将输入字符串按逗号分割成数组
    const inputArray = str.split(',');

    // 检查数组是否非空
    if (isNotEmptyArray(inputArray)) {
        // 创建一个新的数组来存储唯一的元素
        const uniqueArray = [];

        // 遍历输入数组中的每个元素
        for (let index = 0; index < inputArray.length; index++) {
            // 如果当前元素不在uniqueArray中，则将其添加到uniqueArray中
            if (uniqueArray.indexOf(inputArray[index]) === -1) {
                uniqueArray.push(inputArray[index]);

                // 同时将该元素添加到resultString中
                resultString += inputArray[index] + ',';
            }
        }

        // 去除最后一个多余的逗号
        resultString = resultString.slice(0, -1);
    }

    // 返回去重后的字符串
    return isValidString(resultString) ? resultString : str;
}

// endregion

// region 数组处理
// ========================数组处理==========================
/**
 * 判断给定的数组是否为空或undefined
 * @param {Array} array 要判断的数组
 * @return {boolean} 数组是否为空或undefined
 */
function isNotEmptyArray(array) {
    return Array.isArray(array) && array.length > 0;
}

/**
 * 获取数组中指定索引处的元素
 * @param {Array} array - 要获取元素的数组
 * @param {number} index - 要获取的索引
 * @returns {any} 数组中指定索引处的元素
 */
function getElementAtIndex(array, index = 0) {
    // 检查数组是否非空以及索引是否有效
    if (isNotEmptyArray(array) && index >= 0 && index < array.length) {
        // 如果索引在数组范围内，直接返回该索引处的元素
        return array[index];
    }
    // 如果数组为空或索引超出范围，返回数组的第一个元素（如果存在）
    return array?.[0] ?? null;
}

// endregion

// region 字符串解析为JSON
// ==========================字符串解析为JSON=========================

/**
 * 获取url参数字符串
 */
const extractQueryString = (urlString) => {
    const queryIndex = urlString.indexOf('?');
    if (queryIndex === -1) {
        console.log('URL中无查询字符串参数');
        return '';
    }
    return urlString.substring(queryIndex + 1);
};

/**
 * 获取url参数
 */
const extractQueryParameters = (urlString) => {
    const queryIndex = urlString.indexOf('?');
    if (queryIndex === -1) {
        console.log('URL中无查询字符串参数');
        return {};
    }
    // 提取查询字符串部分
    const queryString = urlString.substring(queryIndex + 1);
    // 将查询字符串转换为对象
    try {
        return parseUrlParamsToObject(queryString);
    } catch (error) {
        console.error('解析查询字符串时发生错误:', error);
        return {};
    }
};

/** * 解决小程序类库存在的一个bug，在传递页面参数时，会自动编码一次参数
 * @param encodedString {string} 编码后的字符串
 * @returns {string} 解码后的字符串
 */
function decodeMultipleTimes(encodedString) {
    let decodedString = encodedString;
    let previousDecodedString;
    do {
        previousDecodedString = decodedString;
        decodedString = decodeURIComponent(decodedString);
    } while (decodedString !== previousDecodedString);
    return decodedString;
}


/** * 将url参数解析为对象
 * @param urlParamStr {string} url参数字符串
 * @returns {Object} 解析后的对象
 */
function parseUrlParamsToObject(urlParamStr = '') {
    const args = urlParamStr.split('&');
    const paramsObj = {};

    args.forEach(arg => {
        const [key, value] = arg.split('=');

        // 如果键值对长度大于1，则解码并存储到对象中
        if (key && value) {
            const decodedKey = decodeMultipleTimes(key);
            const decodedValue = decodeMultipleTimes(value);
            paramsObj[decodedKey] = decodedValue;
        }
    });
    return paramsObj;
}


// 将字符串或对象转换为 JSON 对象
function convertToObject(jsonInput) {
    // 如果输入已经是对象，则直接返回
    if (isPlainObject(jsonInput)) {
        return jsonInput;
    }

    // 如果输入是字符串，则尝试解析为 JSON 对象
    if (typeof jsonInput === 'string') {
        return parseJsonString(jsonInput);
    }

    // 输入无效时返回 null
    return null;
}

// 解析 JSON 字符串
function parseJsonString(jsonStr) {
    try {
        return JSON.parse(jsonStr);
    } catch (error) {
        console.log('解析 JSON 字符串时发生错误:', error);
        // 忽略解析错误
    }

    // 返回 null 表示解析失败
    return null;
}

/**
 * 将 JSON 字符串转换为 JSON 对象
 * @param jsonString {string} JSON 字符串
 * @returns {Object} 转换后的 JSON 对象
 */
function parseToJson(jsonString = '{}') {
    // 如果传入的已经是对象或数组，则直接返回
    if (typeof jsonString === 'object' || Array.isArray(jsonString)) {
        return jsonString;
    }

    try {
        // 如果转换成功则返回转换后的对象
        return JSON.parse(jsonString);
    } catch (e) {
        console.error("Error parsing JSON: " + e);
        return {};
    }
}


// endregion

// region 对象字段处理
// ========================对象字段处理==========================
/** * 如果对象中包含该字段，则返回该字段的值，否则返回null
 * @param {Object} obj - 要获取字段值的对象
 * @param {string} field - 要获取的字段
 * @returns {any} 字段的值
 */
function getField(obj, field) {
    return obj && obj[field] ? obj[field] : null;
}

/** * 判断对象是否为空
 * @param {Object} obj - 要判断的对象
 * @returns {boolean} 对象是否为空
 */
function isNonEmptyObject(obj) {
    return obj && Object.keys(obj).length > 0;
}

// endregion

// region 对象类型判断
// ========================对象类型判断==========================
// 判断是否为普通对象
function isPlainObject(value) {
    return Object.prototype.toString.call(value) === '[object Object]';
}

/** * 判断两个字符串是否相等
 * @param {string} str1 - 第一个字符串
 * @param {string} str2 - 第二个字符串
 * @returns {boolean} 两个字符串是否相等
 */
function areStringsEqual(str1, str2) {
    return str1 === str2;
}

/**
 * 判断两个对象是否相等
 * @param {Object} obj1 - 第一个对象
 * @param {Object} obj2 - 第二个对象
 * @returns {boolean} 两个对象是否相等
 */
function areObjectsEqual(obj1, obj2) {
    // 如果两个对象是同一个引用，直接返回 true
    if (obj1 === obj2) {
        return true;
    }

    // 如果其中一个不是对象或是 null，返回 false
    if (obj1 === null || typeof obj1 !== 'object' ||
        obj2 === null || typeof obj2 !== 'object') {
        return false;
    }

    // 获取两个对象的属性名数组
    const keys1 = Object.keys(obj1);
    const keys2 = Object.keys(obj2);

    // 如果属性名数量不同，两个对象肯定不相等
    if (keys1.length !== keys2.length) {
        return false;
    }

    // 递归比较每个属性
    for (let key of keys1) {
        if (!keys2.includes(key) || !areObjectsEqual(obj1[key], obj2[key])) {
            return false;
        }
    }

    // 所有属性都相同，这两个对象相等
    return true;
}

// endregion

export {
    isEmptyStr,
    isTypeString,
    parseToBool,
    parseStrToInt,
    parseToJson,
    containsSubstring,
    isValidString,
    parseUrlParamsToObject,
    extractQueryString,
    extractQueryParameters,
    removeDuplicates,
    isNotEmptyArray,
    getElementAtIndex,
    convertToObject,
    parseJsonString,
    isPlainObject,
    getField,
    areStringsEqual,
    areObjectsEqual,
    isNonEmptyObject,
    isNonEmptyString,
}
