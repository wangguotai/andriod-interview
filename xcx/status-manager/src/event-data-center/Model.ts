class Model {
    state: { [key: string]: any } = {}

    /**
     * 设置数据
     * @param object 
     */
    setModelData(object: object) {
        Object.assign(this.state, object)
    }

    /**
     * 获取数据
     * @param name key
     * @returns 
     */
    getModelData(name:string) {
        return this.state[name]
    }

    /**
     * 清空数据
     */
    cleanModelData(){
        this.state = {}
    }
}

export default Model
