let dataStore = {};
const CashierDataManager = {
    get: (key) => {
        return dataStore[key];
    },
    set: (key, value) => {
        dataStore[key] = value;
    },
    updateObjValue: (key, value) => {
        dataStore[key] = {...dataStore[key], ...value};
    },
    delete: (key) => {
        delete dataStore[key];
    },
    clear: () => {
        dataStore = {};
    }
}
export default CashierDataManager;
